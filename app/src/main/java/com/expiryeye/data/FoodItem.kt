package com.expiryeye.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "food_items", indices = [Index("expiresAt")])
data class FoodItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "Other",
    val quantity: Double = 1.0,
    val unit: String = "pcs",
    val expiresAt: Long,
    val location: String = "Fridge",
    val price: Double? = null,
    @ColumnInfo(defaultValue = "''") val barcode: String = "",
    @ColumnInfo(defaultValue = "''") val brand: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val consumedAt: Long? = null
)

@Serializable
@Entity(tableName = "shopping_items")
data class ShoppingEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val quantity: String = "1",
    val category: String = "Other",
    val checked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao interface FoodDao {
    @Query("SELECT * FROM food_items WHERE consumedAt IS NULL ORDER BY expiresAt ASC") fun active(): Flow<List<FoodItem>>
    @Query("SELECT * FROM food_items ORDER BY createdAt DESC") fun all(): Flow<List<FoodItem>>
    @Query("SELECT * FROM food_items WHERE id = :id LIMIT 1") suspend fun find(id: Long): FoodItem?
    @Query("SELECT * FROM food_items WHERE consumedAt IS NULL") suspend fun activeNow(): List<FoodItem>
    @Query("SELECT * FROM shopping_items ORDER BY checked ASC, category ASC, name COLLATE NOCASE ASC") fun shoppingList(): Flow<List<ShoppingEntry>>
    @Insert suspend fun insert(item: FoodItem): Long
    @Update suspend fun update(item: FoodItem)
    @Delete suspend fun delete(item: FoodItem)
    @Insert suspend fun addToShopping(entry: ShoppingEntry)
    @Query("UPDATE shopping_items SET checked = :checked WHERE id = :id") suspend fun checkShoppingItem(id: Long, checked: Boolean)
    @Query("DELETE FROM shopping_items WHERE id = :id") suspend fun removeShoppingItem(id: Long)
    @Query("DELETE FROM food_items") suspend fun clearFoodItems()
    @Query("DELETE FROM shopping_items") suspend fun clearShoppingItems()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreFoodItems(items: List<FoodItem>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreShoppingItems(items: List<ShoppingEntry>)
    @Transaction suspend fun replaceBackup(foodItems: List<FoodItem>, shoppingItems: List<ShoppingEntry>) {
        clearFoodItems()
        clearShoppingItems()
        restoreFoodItems(foodItems)
        restoreShoppingItems(shoppingItems)
    }
    @Query("UPDATE food_items SET consumedAt = :at WHERE id = :id") suspend fun consume(id: Long, at: Long)
}

@Database(entities = [FoodItem::class, ShoppingEntry::class], version = 4, exportSchema = false)
abstract class ExpiryDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    companion object { @Volatile private var INSTANCE: ExpiryDatabase? = null
        fun get(context: android.content.Context): ExpiryDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ExpiryDatabase::class.java, "expiryeye.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS `shopping_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `quantity` TEXT NOT NULL, `category` TEXT NOT NULL, `checked` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)") }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE `food_items` ADD COLUMN `barcode` TEXT NOT NULL DEFAULT ''") }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE `food_items` ADD COLUMN `brand` TEXT NOT NULL DEFAULT ''") }
        }
    }
}
