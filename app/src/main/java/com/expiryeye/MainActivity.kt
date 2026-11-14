package com.expiryeye

import android.os.Bundle
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.expiryeye.data.*
import com.expiryeye.notifications.ExpiryReminderWorker
import com.expiryeye.notifications.DailyExpiryCheckWorker
import com.expiryeye.domain.daysUntilExpiry
import com.expiryeye.domain.defaultReminderOffsets
import com.expiryeye.domain.consumeQuantity
import com.expiryeye.domain.ReceiptCandidate
import com.expiryeye.data.OpenFoodFactsLookup
import com.expiryeye.data.ProductLookup
import com.expiryeye.ui.BarcodeScannerScreen
import com.expiryeye.ui.ReceiptScannerScreen
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

private val Forest = Color(0xFF174C3A)
private val Mint = Color(0xFFBFE8D2)
private val Canvas = Color(0xFFF7F8F3)
private val defaultStorageLocations = listOf("Fridge", "Freezer", "Pantry")
private val Context.settingsStore by preferencesDataStore(name = "expiryeye_settings")

data class FoodDraft(
    val name: String,
    val category: String,
    val quantity: Double,
    val unit: String,
    val expiresOn: LocalDate,
    val location: String,
    val price: Double?,
    val brand: String,
    val barcode: String,
    val purchasedOn: LocalDate?,
    val openedOn: LocalDate?,
    val notes: String
)

private fun LocalDate.toStoredMillis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

class MainActivity : ComponentActivity() {
    private var notificationItemId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationItemId = intent?.getLongExtra(EXTRA_OPEN_ITEM_ID, -1L)?.takeIf { it >= 0L }
        val dao = ExpiryDatabase.get(this).foodDao()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExpiryViewModel(dao, applicationContext) as T
        }
        setContent { val vm: ExpiryViewModel = viewModel(factory = factory); ExpiryEyeApp(vm, notificationItemId) { notificationItemId = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationItemId = intent.getLongExtra(EXTRA_OPEN_ITEM_ID, -1L).takeIf { it >= 0L }
    }

    companion object { const val EXTRA_OPEN_ITEM_ID = "com.expiryeye.OPEN_ITEM_ID" }
}

class ExpiryViewModel(private val dao: FoodDao, private val appContext: Context) : ViewModel() {
    val active = dao.active().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shopping = dao.shoppingList().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val darkKey = booleanPreferencesKey("dark_theme")
    private val notificationPromptKey = booleanPreferencesKey("notification_prompt_seen")
    private val reminderDaysKey = stringSetPreferencesKey("reminder_days")
    private val recipeFavoritesKey = stringSetPreferencesKey("favorite_recipes")
    private val customLocationsKey = stringSetPreferencesKey("custom_storage_locations")
    private val onboardingKey = booleanPreferencesKey("onboarding_complete")
    private val displayNameKey = stringPreferencesKey("display_name")
    val darkTheme = appContext.settingsStore.data.map { it[darkKey] ?: false }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val notificationPromptSeen = appContext.settingsStore.data.map { it[notificationPromptKey] ?: false }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val onboardingComplete = appContext.settingsStore.data.map { it[onboardingKey] ?: false }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val displayName = appContext.settingsStore.data.map { it[displayNameKey].orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val recipeFavorites = appContext.settingsStore.data.map { it[recipeFavoritesKey] ?: emptySet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val storageLocations = appContext.settingsStore.data.map { prefs ->
        val custom = prefs[customLocationsKey].orEmpty().filter { location ->
            location.isNotBlank() && defaultStorageLocations.none { it.equals(location, ignoreCase = true) }
        }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
        defaultStorageLocations + custom
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultStorageLocations)
    val reminderDays = appContext.settingsStore.data.map { prefs ->
        (prefs[reminderDaysKey]?.mapNotNull(String::toIntOrNull) ?: defaultReminderOffsets).filter { it in listOf(14, 7, 3, 1, 0) }.distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, defaultReminderOffsets)
    private val _transferMessage = MutableStateFlow<String?>(null)
    val transferMessage = _transferMessage.asStateFlow()
    private val _pendingBackup = MutableStateFlow<InventoryBackup?>(null)
    val pendingBackup = _pendingBackup.asStateFlow()
    private val _productPrefill = MutableStateFlow<ProductLookup?>(null)
    val productPrefill = _productPrefill.asStateFlow()
    private val _lookupLoading = MutableStateFlow(false)
    val lookupLoading = _lookupLoading.asStateFlow()
    fun setDarkTheme(enabled: Boolean) { viewModelScope.launch { appContext.settingsStore.edit { it[darkKey] = enabled } } }
    fun markNotificationPromptSeen() { viewModelScope.launch { appContext.settingsStore.edit { it[notificationPromptKey] = true } } }
    fun completeOnboarding() { viewModelScope.launch { appContext.settingsStore.edit { it[onboardingKey] = true } } }
    fun setDisplayName(name: String) { viewModelScope.launch { appContext.settingsStore.edit { it[displayNameKey] = name.trim().take(40) } } }
    fun toggleRecipeFavorite(name: String) { viewModelScope.launch { appContext.settingsStore.edit { prefs ->
        val favorites = prefs[recipeFavoritesKey]?.toMutableSet() ?: mutableSetOf()
        if (!favorites.add(name)) favorites.remove(name)
        prefs[recipeFavoritesKey] = favorites
    } } }
    fun addStorageLocation(value: String) {
        val name = value.trim().take(32)
        if (name.isBlank() || defaultStorageLocations.any { it.equals(name, ignoreCase = true) }) return
        viewModelScope.launch { appContext.settingsStore.edit { prefs ->
            val locations = prefs[customLocationsKey]?.toMutableSet() ?: mutableSetOf()
            if (locations.none { it.equals(name, ignoreCase = true) }) locations.add(name)
            prefs[customLocationsKey] = locations
        } }
    }
    fun removeStorageLocation(name: String) {
        if (defaultStorageLocations.any { it.equals(name, ignoreCase = true) }) return
        viewModelScope.launch { appContext.settingsStore.edit { prefs ->
            prefs[customLocationsKey] = prefs[customLocationsKey].orEmpty().filterNot { it.equals(name, ignoreCase = true) }.toSet()
        } }
    }
    fun setReminderEnabled(days: Int, enabled: Boolean) { viewModelScope.launch { appContext.settingsStore.edit { prefs ->
        val current = prefs[reminderDaysKey]?.toMutableSet() ?: defaultReminderOffsets.map(Int::toString).toMutableSet()
        if (enabled) current.add(days.toString()) else current.remove(days.toString())
        prefs[reminderDaysKey] = current
    } } }
    fun lookupBarcode(code: String) { viewModelScope.launch {
        _lookupLoading.value = true
        val result = runCatching { OpenFoodFactsLookup.find(code) }.getOrElse { ProductLookup(code, message = "Product lookup is unavailable. Enter the details manually.") }
        _productPrefill.value = result
        _lookupLoading.value = false
        result.message?.let { _transferMessage.value = it }
    } }
    fun clearProductPrefill() { _productPrefill.value = null }
    init { viewModelScope.launch { reminderDays.collect { offsets ->
        ExpiryReminderWorker.rescheduleAll(appContext, dao.activeNow(), offsets)
        DailyExpiryCheckWorker.schedule(appContext, offsets)
    } } }
    fun add(draft: FoodDraft) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val item = FoodItem(name = draft.name.trim(), category = draft.category, quantity = draft.quantity, unit = draft.unit, expiresAt = draft.expiresOn.toStoredMillis(), location = draft.location, price = draft.price, brand = draft.brand.trim(), barcode = draft.barcode.trim(), purchasedAt = draft.purchasedOn?.toStoredMillis(), openedAt = draft.openedOn?.toStoredMillis(), notes = draft.notes.trim(), createdAt = now, updatedAt = now)
            val id = dao.insert(item)
            ExpiryReminderWorker.schedule(appContext, item.copy(id = id), reminderDays.first())
        }
    }
    fun update(item: FoodItem, draft: FoodDraft) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = item.copy(name = draft.name.trim(), category = draft.category, quantity = draft.quantity, unit = draft.unit, expiresAt = draft.expiresOn.toStoredMillis(), location = draft.location, price = draft.price, brand = draft.brand.trim(), barcode = draft.barcode.trim(), purchasedAt = draft.purchasedOn?.toStoredMillis(), openedAt = draft.openedOn?.toStoredMillis(), notes = draft.notes.trim(), updatedAt = now)
            dao.update(updated)
            ExpiryReminderWorker.schedule(appContext, updated, reminderDays.first())
        }
    }
    fun consume(item: FoodItem, amount: Double) { viewModelScope.launch {
        val current = dao.find(item.id) ?: return@launch
        if (current.consumedAt != null || current.discardedAt != null) return@launch
        val portion = consumeQuantity(current.quantity, amount) ?: return@launch
        val now = System.currentTimeMillis()
        if (portion.itemFinished) {
            dao.consume(current.id, now)
            ExpiryReminderWorker.cancel(appContext, current.id)
        } else {
            dao.update(current.copy(quantity = portion.remaining, updatedAt = now))
        }
    } }
    fun delete(item: FoodItem) { viewModelScope.launch { dao.delete(item); ExpiryReminderWorker.cancel(appContext, item.id) } }
    fun discard(item: FoodItem, reason: String) { viewModelScope.launch { dao.discard(item.id, System.currentTimeMillis(), reason); ExpiryReminderWorker.cancel(appContext, item.id) } }
    fun addShopping(name: String) { viewModelScope.launch { if (name.isNotBlank()) dao.addToShopping(ShoppingEntry(name = name.trim())) } }
    fun checkShopping(entry: ShoppingEntry, checked: Boolean) { viewModelScope.launch { dao.checkShoppingItem(entry.id, checked) } }
    fun removeShopping(entry: ShoppingEntry) { viewModelScope.launch { dao.removeShoppingItem(entry.id) } }
    fun exportJson(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val backup = InventoryBackup(foodItems = dao.all().first(), shoppingItems = dao.shoppingList().first())
                appContext.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(InventoryBackupCodec.encode(backup)) }
                    ?: error("Could not open the selected file.")
            }
        }.onSuccess { _transferMessage.value = "JSON backup exported." }.onFailure { _transferMessage.value = "Export failed: ${it.message ?: "unknown error"}" }
    }
    fun exportCsv(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val csv = InventoryBackupCodec.toCsv(dao.all().first())
                appContext.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(csv) }
                    ?: error("Could not open the selected file.")
            }
        }.onSuccess { _transferMessage.value = "CSV inventory exported." }.onFailure { _transferMessage.value = "Export failed: ${it.message ?: "unknown error"}" }
    }
    fun inspectBackup(uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val stream = appContext.contentResolver.openInputStream(uri) ?: error("Could not open the selected backup.")
                val input = stream.bufferedReader().use { reader ->
                    val text = StringBuilder(); val buffer = CharArray(8_192)
                    while (true) { val count = reader.read(buffer); if (count < 0) break; require(text.length + count <= 10_000_000) { "Backup file is too large." }; text.append(buffer, 0, count) }
                    text.toString()
                }
                InventoryBackupCodec.decode(input)
            }
        }.onSuccess { _pendingBackup.value = it }.onFailure { _transferMessage.value = "Backup not loaded: ${it.message ?: "invalid file"}" }
    }
    fun confirmRestore() = viewModelScope.launch {
        val backup = _pendingBackup.value ?: return@launch
        runCatching {
            val previous = dao.all().first()
            previous.forEach { ExpiryReminderWorker.cancel(appContext, it.id) }
            dao.replaceBackup(backup.foodItems, backup.shoppingItems)
            ExpiryReminderWorker.rescheduleAll(appContext, backup.foodItems.filter { it.consumedAt == null && it.discardedAt == null }, reminderDays.first())
        }.onSuccess { _transferMessage.value = "Backup restored." }.onFailure { _transferMessage.value = "Restore failed: ${it.message ?: "unknown error"}" }
        _pendingBackup.value = null
    }
    fun cancelRestore() { _pendingBackup.value = null }
    fun clearAllData() = viewModelScope.launch {
        dao.all().first().forEach { ExpiryReminderWorker.cancel(appContext, it.id) }
        dao.replaceBackup(emptyList(), emptyList())
        _transferMessage.value = "Inventory and shopping list cleared."
    }
    fun clearTransferMessage() { _transferMessage.value = null }
}

@Composable fun ExpiryEyeApp(vm: ExpiryViewModel, notificationItemId: Long? = null, onNotificationHandled: () -> Unit = {}) {
    val items by vm.active.collectAsState(); val history by vm.history.collectAsState(); val shopping by vm.shopping.collectAsState()
    val darkTheme by vm.darkTheme.collectAsState()
    val onboardingComplete by vm.onboardingComplete.collectAsState()
    val displayName by vm.displayName.collectAsState()
    val recipeFavorites by vm.recipeFavorites.collectAsState()
    val storageLocations by vm.storageLocations.collectAsState()
    val productPrefill by vm.productPrefill.collectAsState()
    val lookupLoading by vm.lookupLoading.collectAsState()
    val notificationPromptSeen by vm.notificationPromptSeen.collectAsState()
    val transferMessage by vm.transferMessage.collectAsState()
    val pendingBackup by vm.pendingBackup.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.markNotificationPromptSeen() }
    val jsonExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportJson) }
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportCsv) }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::inspectBackup) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirmation by remember { mutableStateOf(false) }
    LaunchedEffect(transferMessage) { transferMessage?.let { snackbarHostState.showSnackbar(it); vm.clearTransferMessage() } }
    var showNotificationInfo by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }; var editItem by remember { mutableStateOf<FoodItem?>(null) }; var adding by remember { mutableStateOf(false) }; var showShopping by remember { mutableStateOf(false) }; var showScanner by remember { mutableStateOf(false) }; var showReceiptScanner by remember { mutableStateOf(false) }; var pendingReceipt by remember { mutableStateOf<List<ReceiptCandidate>?>(null) }; var receiptIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(notificationItemId, items) {
        val itemId = notificationItemId ?: return@LaunchedEffect
        items.firstOrNull { it.id == itemId }?.let { item ->
            tab = 1
            editItem = item
            onNotificationHandled()
        }
    }
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme(primary = Mint, secondary = Color(0xFF78C99E)) else lightColorScheme(primary = Forest, secondary = Color(0xFF3A8060), background = Canvas, surface = Color.White, onBackground = Color(0xFF17231D))) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = { if (onboardingComplete && !showScanner && !showReceiptScanner) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(Icons.Rounded.Home to "Home", Icons.Rounded.Inventory2 to "Food", Icons.Rounded.Restaurant to "Recipes", Icons.Rounded.BarChart to "Stats", Icons.Rounded.Settings to "Settings").forEachIndexed { i, pair ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(pair.first, null) }, label = { Text(pair.second) })
                }
            } },
            floatingActionButton = { if (onboardingComplete && tab <= 1 && !showScanner && !showReceiptScanner) FloatingActionButton(onClick = { editItem = null; vm.clearProductPrefill(); adding = true }, containerColor = Forest, contentColor = Color.White) { Icon(Icons.Rounded.Add, "Add food") } }
        ) { padding ->
            if (!onboardingComplete) OnboardingScreen(Modifier.padding(padding)) { addFirst ->
                vm.completeOnboarding(); if (addFirst) adding = true
            } else when (tab) {
                0 -> HomeScreen(items, vm, padding, false, storageLocations, displayName = displayName, onEdit = { editItem = it })
                1 -> if (showScanner) BarcodeScannerScreen(onClose = { showScanner = false }, onBarcode = { showScanner = false; vm.lookupBarcode(it) })
                    else HomeScreen(items, vm, padding, true, storageLocations, displayName = displayName, onEdit = { editItem = it }, onScan = { showScanner = true })
                2 -> RecipesScreen(items, padding, recipeFavorites, vm::toggleRecipeFavorite, vm::addShopping)
                3 -> StatisticsScreen(items, history, padding)
                else -> when {
                    showShopping -> ShoppingScreen(shopping, padding, vm::addShopping, vm::checkShopping, vm::removeShopping, onBack = { showShopping = false })
                    showReceiptScanner -> ReceiptScannerScreen(onClose = { showReceiptScanner = false }, onConfirm = { receipt -> pendingReceipt = receipt; receiptIndex = 0; showReceiptScanner = false })
                    else -> SettingsScreen(
                    padding, darkTheme, vm::setDarkTheme, displayName, vm::setDisplayName, vm.reminderDays.collectAsState().value, storageLocations, vm::addStorageLocation, vm::removeStorageLocation, vm::setReminderEnabled,
                    onNotificationSettings = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
                    onShopping = { showShopping = true },
                    onScanReceipt = { showReceiptScanner = true },
                    onExportJson = { jsonExportLauncher.launch("expiryeye-backup.json") },
                    onExportCsv = { csvExportLauncher.launch("expiryeye-inventory.csv") },
                    onImport = { backupImportLauncher.launch(arrayOf("application/json", "text/*")) },
                    onClear = { showClearConfirmation = true }
                )
                }
            }
        }
        LaunchedEffect(productPrefill) { if (productPrefill != null) adding = true }
        if (lookupLoading) AlertDialog(onDismissRequest = { }, title = { Text("Looking up product") }, text = { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(modifier = Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text("Expiration date will still need to be entered.") } }, confirmButton = {})
        if (onboardingComplete && pendingReceipt == null && (adding || editItem != null)) FoodEditor(editItem, storageLocations, initialName = productPrefill?.name.orEmpty(), initialBrand = productPrefill?.brand.orEmpty(), initialBarcode = productPrefill?.barcode.orEmpty(), initialCategory = productPrefill?.category ?: "Other", onDismiss = { adding = false; editItem = null; vm.clearProductPrefill() }, onSave = { old, draft ->
            if (old == null) vm.add(draft) else vm.update(old, draft)
            vm.clearProductPrefill()
            if (old == null && !notificationPromptSeen) {
                val alreadyAllowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!alreadyAllowed) showNotificationInfo = true else vm.markNotificationPromptSeen()
            }
            adding = false; editItem = null
        }, onDelete = { editItem?.let(vm::delete); editItem = null; vm.clearProductPrefill() })
        val receiptCandidate = pendingReceipt?.getOrNull(receiptIndex)
        if (onboardingComplete && receiptCandidate != null) FoodEditor(null, storageLocations, initialName = receiptCandidate.name, initialQuantity = receiptCandidate.quantity, initialPrice = receiptCandidate.price, onDismiss = { pendingReceipt = null; receiptIndex = 0 }, onSave = { _, draft ->
            vm.add(draft)
            if (receiptIndex == pendingReceipt.orEmpty().lastIndex && !notificationPromptSeen) {
                val alreadyAllowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!alreadyAllowed) showNotificationInfo = true else vm.markNotificationPromptSeen()
            }
            receiptIndex += 1
            if (receiptIndex >= pendingReceipt.orEmpty().size) { pendingReceipt = null; receiptIndex = 0 }
        }, onDelete = { pendingReceipt = null; receiptIndex = 0 })
        if (showNotificationInfo) AlertDialog(onDismissRequest = { showNotificationInfo = false; vm.markNotificationPromptSeen() },
            title = { Text("A timely freshness reminder?") },
            text = { Text("ExpiryEye can remind you before a tracked date. Choose intervals in Settings; Android may delay background notifications.") },
            confirmButton = { TextButton(onClick = { showNotificationInfo = false; vm.markNotificationPromptSeen(); if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow reminders") } },
            dismissButton = { TextButton(onClick = { showNotificationInfo = false; vm.markNotificationPromptSeen() }) { Text("Not now") } })
        pendingBackup?.let { backup -> AlertDialog(onDismissRequest = vm::cancelRestore,
            title = { Text("Replace app data?") },
            text = { Text("This backup contains ${backup.foodItems.size} food records and ${backup.shoppingItems.size} shopping items. Restoring it will replace the data currently stored in ExpiryEye.") },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Replace and restore") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } }) }
        if (showClearConfirmation) AlertDialog(onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear all app data?") }, text = { Text("This permanently removes all inventory history and shopping list items from this device.") },
            confirmButton = { TextButton(onClick = { showClearConfirmation = false; vm.clearAllData() }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") } })
    }
}

@Composable private fun OnboardingScreen(modifier: Modifier = Modifier, onFinish: (addFirstItem: Boolean) -> Unit) {
    val slides = listOf(
        Triple(Icons.Rounded.Kitchen, "Know what you have.", "Keep your fridge, freezer, and pantry in one calm, easy-to-scan place."),
        Triple(Icons.Rounded.NotificationsActive, "Catch dates in time.", "Choose local reminders that help you use food before its date."),
        Triple(Icons.Rounded.Restaurant, "Make something good.", "Find simple meal ideas from the ingredients already at home.")
    )
    var page by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onFinish(false) }) { Text("Skip") } }
        androidx.compose.animation.AnimatedContent(targetState = page, label = "onboarding-slide") { index ->
            val slide = slides[index]
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(150.dp).background(Mint.copy(alpha = .5f), RoundedCornerShape(48.dp)), contentAlignment = Alignment.Center) { Icon(slide.first, null, tint = Forest, modifier = Modifier.size(72.dp)) }
                Spacer(Modifier.height(32.dp)); Text(slide.second, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(10.dp)); Text(slide.third, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 25.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { slides.indices.forEach { index -> Box(Modifier.padding(4.dp).size(if (page == index) 20.dp else 8.dp, 8.dp).background(if (page == index) Forest else Mint, RoundedCornerShape(8.dp))) } }
            if (page < slides.lastIndex) Button(onClick = { page++ }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("Continue") }
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onFinish(true) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text("Add my first food") }
                TextButton(onClick = { onFinish(false) }, modifier = Modifier.fillMaxWidth()) { Text("Explore first") }
            }
        }
    }
}

@Composable private fun HomeScreen(items: List<FoodItem>, vm: ExpiryViewModel, padding: PaddingValues, inventory: Boolean, storageLocations: List<String>, displayName: String, onEdit: (FoodItem) -> Unit, onScan: () -> Unit = {}) {
    val today = LocalDate.now(); var query by remember { mutableStateOf("") }; var filter by remember { mutableStateOf("All") }; var status by remember { mutableStateOf("Any status") }; var sort by remember { mutableStateOf("Soonest") }
    val soon = items.filter { days(it, today) in 0..7 }; val expired = items.count { days(it, today) < 0 }
    val visible = items.filter { food -> food.name.contains(query, true) && (filter == "All" || food.location == filter) && when (status) { "Expiring soon" -> days(food, today) in 0..7; "Expired" -> days(food, today) < 0; else -> true } }
        .let { list -> when (sort) { "A to Z" -> list.sortedBy { it.name.lowercase() }; "Recently added" -> list.sortedByDescending { it.createdAt }; else -> list.sortedBy { it.expiresAt } } }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 26.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(if (inventory) "YOUR KITCHEN" else greeting(displayName), style = MaterialTheme.typography.labelLarge, color = Forest, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(5.dp)); Text(if (inventory) "Food inventory" else "Freshness, at a glance.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!inventory) item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("In kitchen", items.size.toString(), Icons.Rounded.Inventory2, Modifier.weight(1f)); MetricCard("Use soon", soon.size.toString(), Icons.Rounded.Schedule, Modifier.weight(1f)); MetricCard("Expired", expired.toString(), Icons.Rounded.WarningAmber, Modifier.weight(1f))
        } }
        if (!inventory && soon.isNotEmpty()) item { SuggestionCard(soon) }
        item { if (inventory) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search your food") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
            OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan barcode") }
        } }
        if (inventory) item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { (listOf("All") + storageLocations).forEach { FilterChip(filter == it, { filter = it }, label = { Text(it) }) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Any status", "Expiring soon", "Expired").forEach { FilterChip(status == it, { status = it }, label = { Text(it) }) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Soonest", "A to Z", "Recently added").forEach { FilterChip(sort == it, { sort = it }, label = { Text(it) }) } }
        } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (inventory) "All items" else "Use these first", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); if (soon.isNotEmpty() && !inventory) Text("${soon.size} soon", color = Forest, fontWeight = FontWeight.SemiBold) } }
        if (visible.isEmpty()) item { if (items.isEmpty()) EmptyCard() else Text("No food matches these filters yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) } else this.items(visible, key = { it.id }) { food -> FoodCard(food, today, onConsume = { amount -> vm.consume(food, amount) }, onDiscard = { reason -> vm.discard(food, reason) }, onClick = { onEdit(food) }) }
    }
}

@Composable private fun SuggestionCard(soon: List<FoodItem>) { Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Forest)) { Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Lightbulb, null, tint = Mint); Spacer(Modifier.width(12.dp)); Column { Text("A little freshness nudge", color = Mint, fontWeight = FontWeight.SemiBold); Text("${soon.size} ${if (soon.size == 1) "item needs" else "items need"} using this week. ${soon.take(2).joinToString { it.name }} could be a good start.", color = Color.White) } } } }

@Composable private fun MetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) { Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun FoodCard(item: FoodItem, today: LocalDate, onConsume: (Double) -> Unit, onDiscard: (String) -> Unit, onClick: () -> Unit) {
    var showDiscardDialog by remember(item.id) { mutableStateOf(false) }
    var discardReason by remember(item.id) { mutableStateOf("Expired") }
    var showConsumeDialog by remember(item.id) { mutableStateOf(false) }
    var consumeAmount by remember(item.id, item.quantity) { mutableStateOf(item.quantity.toString()) }
    val left = days(item, today); val statusColor = when { left < 0 -> Color(0xFF9C3D38); left == 0 -> Color(0xFFD85D35); left <= 2 -> Color(0xFFE19531); left <= 7 -> Color(0xFFB68C24); else -> Forest }
    Card(onClick = onClick, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).background(Mint.copy(alpha = .55f), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Eco, null, tint = Forest) }; Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface); Text(listOf(item.brand, item.location, quantityText(item), item.category).filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); Text(when { left < 0 -> "Expired ${-left}d ago"; left == 0 -> "Expires today"; left == 1 -> "Expires tomorrow"; else -> "$left days left" }, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); if (item.openedAt != null) Text("Opened ${Instant.ofEpochMilli(item.openedAt).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM"))}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        IconButton(onClick = { consumeAmount = item.quantity.toString(); showConsumeDialog = true }) { Icon(Icons.Rounded.CheckCircle, "Consume quantity", tint = Forest) }
        IconButton(onClick = { showDiscardDialog = true }) { Icon(Icons.Rounded.Delete, "Mark discarded", tint = MaterialTheme.colorScheme.error) }
    } }
    if (showDiscardDialog) AlertDialog(
        onDismissRequest = { showDiscardDialog = false },
        title = { Text("Discard ${item.name}?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Choose a reason to improve your waste summaries."); listOf("Expired", "Spoiled", "Unwanted", "Other").forEach { reason -> FilterChip(selected = discardReason == reason, onClick = { discardReason = reason }, label = { Text(reason) }) } } },
        confirmButton = { TextButton(onClick = { showDiscardDialog = false; onDiscard(discardReason) }) { Text("Mark discarded") } },
        dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("Cancel") } }
    )
    if (showConsumeDialog) {
        val amount = consumeAmount.toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { showConsumeDialog = false },
            title = { Text("Use ${item.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("${quantityText(item)} available"); OutlinedTextField(consumeAmount, { consumeAmount = it }, label = { Text("Amount used") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true) } },
            confirmButton = { TextButton(onClick = { amount?.let(onConsume); showConsumeDialog = false }, enabled = amount?.let { it.isFinite() && it > 0 && it <= item.quantity } == true) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showConsumeDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable private fun EmptyCard() { Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F2EA))) { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.Spa, null, tint = Forest, modifier = Modifier.size(36.dp)); Spacer(Modifier.height(10.dp)); Text("Your kitchen is waiting", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Forest); Text("Add your first food item to keep an eye on freshness.", color = Color(0xFF52645A)) } } }

private data class Recipe(
    val name: String,
    val ingredients: Set<String>,
    val time: Int,
    val category: String,
    val steps: String,
    val vegetarian: Boolean = true,
    val highProtein: Boolean = false,
    val lowCost: Boolean = true
)
private val recipes = listOf(
    Recipe("Garden omelette", setOf("egg", "tomato", "spinach", "cheese"), 15, "Breakfast", "Whisk eggs, fold in chopped vegetables and cheese, then cook gently until set.", highProtein = true),
    Recipe("Creamy tomato pasta", setOf("pasta", "tomato", "milk", "cheese", "onion"), 25, "Dinner", "Cook pasta. Simmer tomatoes and onion, stir in milk and cheese, then toss together."),
    Recipe("Apple overnight oats", setOf("oats", "apple", "milk", "yogurt"), 5, "Breakfast", "Mix oats, milk and yogurt. Top with diced apple and chill overnight."),
    Recipe("Spinach grilled cheese", setOf("bread", "spinach", "cheese", "butter"), 12, "Lunch", "Layer spinach and cheese between bread slices; toast in butter until golden.", highProtein = true),
    Recipe("Quick veggie rice", setOf("rice", "carrot", "peas", "egg", "soy sauce"), 20, "Dinner", "Stir-fry vegetables, add cooked rice, then finish with egg and soy sauce.", highProtein = true),
    Recipe("Lemon chicken rice", setOf("chicken", "rice", "lemon", "yogurt"), 30, "Dinner", "Cook chicken thoroughly. Serve with warm rice and a lemon-yogurt sauce.", vegetarian = false, highProtein = true, lowCost = false),
    Recipe("Tuna tomato pasta", setOf("tuna", "pasta", "tomato", "onion"), 18, "Lunch", "Cook pasta. Warm tomato and onion, fold in drained tuna, and toss together.", vegetarian = false, highProtein = true)
)

private fun matchedIngredients(recipe: Recipe, items: List<FoodItem>): Set<String> = recipe.ingredients.filter { required ->
    items.any { food -> food.name.contains(required, ignoreCase = true) || required.contains(food.name, ignoreCase = true) }
}.toSet()

@Composable private fun RecipesScreen(
    items: List<FoodItem>, padding: PaddingValues, favorites: Set<String>,
    onToggleFavorite: (String) -> Unit, onAddShopping: (String) -> Unit
) {
    val today = LocalDate.now()
    var selected by remember { mutableStateOf<Recipe?>(null) }
    var filter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Favorites", "Quick", "Breakfast", "Lunch", "Dinner", "Vegetarian", "High protein", "Low cost", "Use soon")
    val visibleRecipes = recipes.filter { recipe -> when (filter) {
        "Favorites" -> recipe.name in favorites
        "Quick" -> recipe.time <= 20
        "Breakfast", "Lunch", "Dinner" -> recipe.category == filter
        "Vegetarian" -> recipe.vegetarian
        "High protein" -> recipe.highProtein
        "Low cost" -> recipe.lowCost
        "Use soon" -> items.any { food -> days(food, today) in 0..7 && recipe.ingredients.any { required -> food.name.contains(required, true) || required.contains(food.name, true) } }
        else -> true
    } }.sortedWith(
        compareByDescending<Recipe> { recipe -> items.count { food -> days(food, today) in 0..7 && recipe.ingredients.any { required -> food.name.contains(required, true) || required.contains(food.name, true) } } }
            .thenByDescending { recipe -> matchedIngredients(recipe, items).size }
            .thenBy { it.time }
    )
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 26.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("COOK WITH WHAT YOU HAVE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp); Text("A little inspiration", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Text("Local ideas matched to your kitchen.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (items.isEmpty()) item { Text("Add food to your inventory for personalized ingredient matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { filters.forEach { option -> FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) }) } } }
        if (visibleRecipes.isEmpty()) item { Text(if (filter == "Favorites") "Save recipes with the heart button to find them here." else "No recipes match this filter yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp)) }
        else this.items(visibleRecipes, key = { it.name }) { recipe ->
            val matched = matchedIngredients(recipe, items); val missing = recipe.ingredients - matched
            Card(onClick = { selected = recipe }, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(recipe.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text("${recipe.category} · ${recipe.time} min", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text("${matched.size}/${recipe.ingredients.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); IconButton(onClick = { onToggleFavorite(recipe.name) }) { Icon(Icons.Rounded.Favorite, contentDescription = if (recipe.name in favorites) "Remove from favorites" else "Add to favorites", tint = if (recipe.name in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                LinearProgressIndicator(progress = { matched.size.toFloat() / recipe.ingredients.size }, modifier = Modifier.fillMaxWidth(), color = Forest, trackColor = Mint)
                Text(if (missing.isEmpty()) "You have everything" else "Need: ${missing.joinToString()}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } }
        }
    }
    selected?.let { recipe ->
        val matched = matchedIngredients(recipe, items)
        val missing = recipe.ingredients - matched
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(recipe.name, modifier = Modifier.weight(1f)); IconButton(onClick = { onToggleFavorite(recipe.name) }) { Icon(Icons.Rounded.Favorite, contentDescription = if (recipe.name in favorites) "Remove from favorites" else "Add to favorites", tint = if (recipe.name in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } } },
            text = { Text("${matched.size} of ${recipe.ingredients.size} ingredients available · ${recipe.time} minutes\n${recipe.ingredients.joinToString()}\n\n${recipe.steps}") },
            confirmButton = { TextButton(onClick = { missing.forEach(onAddShopping); selected = null }) { Text(if (missing.isEmpty()) "Done" else "Add missing to list") } }
        )
    }
}

@Composable private fun StatisticsScreen(active: List<FoodItem>, history: List<FoodItem>, padding: PaddingValues) {
    val today = LocalDate.now()
    val consumed = history.filter { it.consumedAt != null }
    var period by remember { mutableStateOf("Week") }
    val periodStart = when (period) { "Month" -> today.minusMonths(1).plusDays(1); "Year" -> today.minusYears(1).plusDays(1); else -> today.minusDays(6) }
    val periodStartMillis = periodStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val addedInPeriod = history.count { it.createdAt >= periodStartMillis }
    val consumedInPeriod = history.filter { (it.consumedAt ?: Long.MIN_VALUE) >= periodStartMillis }.sortedByDescending { it.consumedAt }
    val discardedInPeriod = history.filter { (it.discardedAt ?: Long.MIN_VALUE) >= periodStartMillis }
    val estimatedValue = consumedInPeriod.mapNotNull { it.price }.sum()
    val estimatedWaste = discardedInPeriod.mapNotNull { it.price }.sum()
    val commonDiscardReason = discardedInPeriod.groupingBy { it.discardReason ?: "Other" }.eachCount().maxByOrNull { it.value }?.key
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val weeklyConsumed = week.map { day -> consumed.count { item -> Instant.ofEpochMilli(item.consumedAt!!).atZone(ZoneId.systemDefault()).toLocalDate() == day } }
    val maxDaily = weeklyConsumed.maxOrNull()?.coerceAtLeast(1) ?: 1
    val expiredNow = active.count { days(it, today) < 0 }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 28.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("YOUR IMPACT", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp); Text("Small wins add up.", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Text("Based on the items you track on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Week", "Month", "Year").forEach { option -> FilterChip(selected = period == option, onClick = { period = option }, label = { Text(option) }) } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MetricCard("Added", addedInPeriod.toString(), Icons.Rounded.Add, Modifier.weight(1f)); MetricCard("Consumed", consumedInPeriod.size.toString(), Icons.Rounded.Favorite, Modifier.weight(1f)); MetricCard("Expired now", expiredNow.toString(), Icons.Rounded.WarningAmber, Modifier.weight(1f)) } }
        item { Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Estimated value enjoyed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text(if (consumedInPeriod.any { it.price != null }) "${"%.2f".format(estimatedValue)} in tracked items" else "Add prices to items to estimate value here.", color = MaterialTheme.colorScheme.primary, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text("Estimate from prices you entered for items marked consumed during this period. It is not exact savings.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) } } }
        item { Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Recorded waste", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface); Text("${discardedInPeriod.size} items marked discarded${commonDiscardReason?.let { " · most often: $it" }.orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant); if (discardedInPeriod.any { it.price != null }) Text("Entered prices total ${"%.2f".format(estimatedWaste)}; this is an estimate.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) else Text("Add prices to discarded items to estimate their value.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) } } }
        item { Text("Weekly consumption", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
        item { Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { week.forEachIndexed { index, day -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text(day.format(DateTimeFormatter.ofPattern("EEE")), modifier = Modifier.width(40.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); LinearProgressIndicator(progress = { weeklyConsumed[index].toFloat() / maxDaily }, modifier = Modifier.weight(1f), color = Forest, trackColor = Mint); Text(weeklyConsumed[index].toString(), modifier = Modifier.width(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } } } } }
        item { Text("Recently consumed · $period", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
        if (consumedInPeriod.isEmpty()) item { Text("Mark food as consumed and your progress will show here.", color = MaterialTheme.colorScheme.onSurfaceVariant) } else this.items(consumedInPeriod.take(10)) { Text("✓  ${it.name}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(15.dp)) }
    }
}

@Composable private fun ShoppingScreen(
    entries: List<ShoppingEntry>, padding: PaddingValues,
    onAdd: (String) -> Unit, onCheck: (ShoppingEntry, Boolean) -> Unit,
    onRemove: (ShoppingEntry) -> Unit, onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Text("Settings") }; Text("SHOP SMARTER", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp); Text("Shopping list", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.weight(1f), label = { Text("Add an item") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            FilledIconButton(onClick = { if (name.isNotBlank()) { onAdd(name); name = "" } }, enabled = name.isNotBlank()) { Icon(Icons.Rounded.Add, "Add") }
        } }
        if (entries.isEmpty()) item { Text("Your shopping list is clear. Add items here or send missing recipe ingredients to this list.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp)) }
        else this.items(entries, key = { it.id }) { entry ->
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = entry.checked, onCheckedChange = { onCheck(entry, it) })
                    Text(entry.name, modifier = Modifier.weight(1f), color = if (entry.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { onRemove(entry) }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(padding: PaddingValues, darkTheme: Boolean, onDarkTheme: (Boolean) -> Unit, displayName: String, onDisplayName: (String) -> Unit, reminderDays: List<Int>, storageLocations: List<String>, onAddStorageLocation: (String) -> Unit, onRemoveStorageLocation: (String) -> Unit, onReminderEnabled: (Int, Boolean) -> Unit, onNotificationSettings: () -> Unit, onShopping: () -> Unit, onScanReceipt: () -> Unit, onExportJson: () -> Unit, onExportCsv: () -> Unit, onImport: () -> Unit, onClear: () -> Unit) {
    var name by remember(displayName) { mutableStateOf(displayName) }
    var newStorageLocation by remember { mutableStateOf("") }
    val customStorageLocations = storageLocations.filterNot { location -> defaultStorageLocations.any { it.equals(location, ignoreCase = true) } }
    LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 28.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("MAKE IT YOURS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp); Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground) }
        item { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Your name", fontWeight = FontWeight.SemiBold); Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it.take(40) }, modifier = Modifier.weight(1f), placeholder = { Text("What should we call you?") }, singleLine = true); TextButton(onClick = { onDisplayName(name) }) { Text("Save") } } } } }
        item { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text("Dark theme", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Switch(darkTheme, onDarkTheme) } } }
        item { SettingRow("Language", "English", Icons.Rounded.Language) }
        item { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Storage locations", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newStorageLocation, { newStorageLocation = it.take(32) }, modifier = Modifier.weight(1f), label = { Text("Add a place") }, singleLine = true)
                FilledIconButton(onClick = { onAddStorageLocation(newStorageLocation); newStorageLocation = "" }, enabled = newStorageLocation.isNotBlank()) { Icon(Icons.Rounded.Add, "Add location") }
            }
            if (customStorageLocations.isEmpty()) Text("Fridge, freezer, and pantry are always available.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { customStorageLocations.forEach { location -> AssistChip(onClick = { onRemoveStorageLocation(location) }, label = { Text(location) }, leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $location") }) } }
        } } }
        item { Text("Expiration reminders", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Remind me", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf(14, 7, 3, 1, 0).forEach { days ->
                val label = if (days == 0) "Day of" else "$days days"
                FilterChip(selected = days in reminderDays, onClick = { onReminderEnabled(days, days !in reminderDays) }, label = { Text(label) })
            } }
            Text("WorkManager delivery can be delayed by Android background limits.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        } } }
        item { ActionSetting("Notification permission", "Manage reminders in Android settings", Icons.Rounded.Notifications, onNotificationSettings) }
        item { Card(onClick = onShopping, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.ShoppingCart, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column { Text("Shopping list", fontWeight = FontWeight.SemiBold); Text("Keep track of what to pick up", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } } } }
        item { ActionSetting("Scan a receipt", "On-device OCR. Review product names and prices.", Icons.Rounded.ReceiptLong, onScanReceipt) }
        item { SettingRow("Privacy", "Inventory is stored on this device", Icons.Rounded.Lock) }
        item { Text("Your data", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item { ActionSetting("Export backup as JSON", "Includes inventory history and shopping list", Icons.Rounded.FileDownload, onExportJson) }
        item { ActionSetting("Export inventory as CSV", "A spreadsheet-friendly inventory export", Icons.Rounded.TableView, onExportCsv) }
        item { ActionSetting("Restore JSON backup", "Replace this device's ExpiryEye data", Icons.Rounded.FileUpload, onImport) }
        item { ActionSetting("Clear all data", "Delete inventory history and shopping items", Icons.Rounded.DeleteForever, onClear, danger = true) }
        item { Text("ExpiryEye · Version 1.0.0", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable private fun SettingRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } } } }

@Composable private fun ActionSetting(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, danger: Boolean = false) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (danger) Color(0xFFB33B32) else MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Column { Text(title, fontWeight = FontWeight.SemiBold, color = if (danger) Color(0xFFB33B32) else MaterialTheme.colorScheme.onSurface); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OptionalDateField(label: String, value: LocalDate?, onChange: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Rounded.CalendarMonth, null)
            Spacer(Modifier.width(8.dp))
            Text(value?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: label)
        }
        if (value != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false }) { Text("Select") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        ) { DatePicker(state = picker) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FoodEditor(item: FoodItem?, storageLocations: List<String>, initialName: String = "", initialBrand: String = "", initialBarcode: String = "", initialCategory: String = "Other", initialQuantity: Double = 1.0, initialPrice: Double? = null, onDismiss: () -> Unit, onSave: (FoodItem?, FoodDraft) -> Unit, onDelete: () -> Unit) {
    val editorKey = item?.id?.toString() ?: "$initialBarcode:$initialName"
    var name by remember(editorKey) { mutableStateOf(item?.name ?: initialName) }; var brand by remember(editorKey) { mutableStateOf(item?.brand ?: initialBrand) }; var barcode by remember(editorKey) { mutableStateOf(item?.barcode ?: initialBarcode) }; var category by remember(editorKey) { mutableStateOf(item?.category ?: initialCategory) }; var quantity by remember(editorKey) { mutableStateOf(item?.quantity?.toString() ?: initialQuantity.toString()) }; var unit by remember(editorKey) { mutableStateOf(item?.unit ?: "pcs") }
    var date by remember(editorKey) { mutableStateOf(item?.let { Instant.ofEpochMilli(it.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now().plusDays(7)) }; var dateConfirmed by remember(editorKey) { mutableStateOf(item != null) }; var location by remember(editorKey) { mutableStateOf(item?.location ?: "Fridge") }; var price by remember(editorKey) { mutableStateOf(item?.price?.toString() ?: initialPrice?.toString().orEmpty()) }; var dateOpen by remember(editorKey) { mutableStateOf(false) }
    var purchasedOn by remember(editorKey) { mutableStateOf(item?.purchasedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }) }
    var openedOn by remember(editorKey) { mutableStateOf(item?.openedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }) }
    var notes by remember(editorKey) { mutableStateOf(item?.notes.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (item == null) "Add to your kitchen" else "Edit food", fontWeight = FontWeight.Bold) }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Food name") }, singleLine = true)
            OutlinedTextField(brand, { brand = it }, label = { Text("Brand (optional)") }, singleLine = true)
            OutlinedTextField(barcode, { barcode = it.filter { ch -> ch in '0'..'9' }.take(14) }, label = { Text("Barcode") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Category", color = MaterialTheme.colorScheme.onSurfaceVariant); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Fruits", "Vegetables", "Dairy", "Meat", "Fish & seafood", "Bakery", "Beverages", "Frozen food", "Canned food", "Snacks", "Condiments", "Grains & pasta", "Other").forEach { FilterChip(category == it, { category = it }, label = { Text(it) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(quantity, { quantity = it }, modifier = Modifier.weight(1f), label = { Text("Quantity") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true); OutlinedTextField(unit, { unit = it }, modifier = Modifier.weight(1f), label = { Text("Unit") }, singleLine = true) }
            OptionalDateField("Purchase date", purchasedOn, { purchasedOn = it })
            OptionalDateField("Date opened", openedOn, { openedOn = it })
            OutlinedButton(onClick = { dateOpen = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(if (dateConfirmed) "Expires ${date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}" else "Choose expiration date") }
            Text("Storage", color = MaterialTheme.colorScheme.onSurfaceVariant); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { (storageLocations + location).distinct().forEach { FilterChip(location == it, { location = it }, label = { Text(it) }) } }
            OutlinedTextField(price, { price = it }, label = { Text("Price (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(notes, { notes = it.take(500) }, label = { Text("Notes (optional)") }, minLines = 2, maxLines = 4)
        }
    }, confirmButton = { TextButton(onClick = {
        val q = quantity.toDoubleOrNull() ?: 0.0
        val parsedPrice = price.toDoubleOrNull()
        if (name.isNotBlank() && q.isFinite() && q > 0 && dateConfirmed && (price.isBlank() || parsedPrice != null && parsedPrice.isFinite() && parsedPrice >= 0)) {
            onSave(item, FoodDraft(name, category, q, unit.ifBlank { "pcs" }, date, location, parsedPrice, brand, barcode, purchasedOn, openedOn, notes))
        }
    }, enabled = name.isNotBlank() && (quantity.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true) && dateConfirmed && (price.isBlank() || price.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true)) { Text("Save") } }, dismissButton = { Row { if (item != null) TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFF9C3D38)) }; TextButton(onClick = onDismiss) { Text("Cancel") } } })
    if (dateOpen) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateOpen = false }, confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); dateConfirmed = true }; dateOpen = false }) { Text("Select") } }, dismissButton = { TextButton(onClick = { dateOpen = false }) { Text("Cancel") } }) { DatePicker(state = picker) }
    }
}

private fun greeting(name: String): String {
    val time = when (LocalTime.now().hour) { in 5..11 -> "GOOD MORNING"; in 12..17 -> "GOOD AFTERNOON"; else -> "GOOD EVENING" }
    return if (name.isBlank()) time else "$time, ${name.uppercase()}"
}
private fun days(item: FoodItem, today: LocalDate): Int = daysUntilExpiry(Instant.ofEpochMilli(item.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate(), today)
private fun quantityText(item: FoodItem): String = if (item.quantity % 1.0 == 0.0) "${item.quantity.toInt()} ${item.unit}" else "${item.quantity} ${item.unit}"
