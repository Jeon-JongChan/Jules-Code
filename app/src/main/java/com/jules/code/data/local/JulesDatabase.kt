package com.jules.code.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 사용자의 API 키 프로필(최대 3개 또는 다중 프로필 전환 지원)을 저장하는 Room 엔티티입니다.
 */
@Entity(tableName = "api_key_profiles")
data class ApiKeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val apiKey: String,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 오프라인 조회 및 빠른 렌더링을 위해 캐시하는 코딩 세션 Room 엔티티입니다.
 */
@Entity(tableName = "cached_sessions")
data class CachedSessionEntity(
    @PrimaryKey val sessionId: String,
    val name: String,
    val title: String,
    val prompt: String,
    val state: String,
    val repoDisplay: String,
    val branchDisplay: String,
    val prUrl: String,
    val prTitle: String,
    val createTime: String,
    val updateTime: String,
    val isPinned: Boolean = false
)

/**
 * 오프라인 조회 및 빠른 선택을 위해 캐시하는 GitHub 소스(저장소) Room 엔티티입니다.
 */
@Entity(tableName = "cached_sources")
data class CachedSourceEntity(
    @PrimaryKey val name: String,
    val id: String,
    val owner: String,
    val repo: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val branchesCsv: String
)

/**
 * Jules 웹의 Scheduled Tasks(반복 작업) 및 자주 쓰는 프롬프트 템플릿을 관리하는 Room 엔티티입니다.
 */
@Entity(tableName = "scheduled_templates")
data class ScheduledTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val prompt: String,
    val sourceName: String,
    val branchName: String,
    val cadence: String, // "MANUAL", "DAILY", "WEEKLY"
    val requirePlanApproval: Boolean = false,
    val autoCreatePr: Boolean = true,
    val lastRunAt: Long = 0L
)

/**
 * 앱 전역 환경 설정(커밋 작성자 표시 모드, 폴링 주기 등)을 저장하는 Room 엔티티입니다.
 */
@Entity(tableName = "app_preferences")
data class AppPreferenceEntity(
    @PrimaryKey val prefKey: String,
    val prefValue: String
)

/**
 * Jules 로컬 데이터를 관리하는 통합 Room DAO 인터페이스입니다.
 */
@Dao
interface JulesDao {
    // --- API Keys ---
    @Query("SELECT * FROM api_key_profiles ORDER BY isActive DESC, createdAt DESC")
    fun observeApiKeys(): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_key_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActiveApiKey(): Flow<ApiKeyEntity?>

    @Query("SELECT * FROM api_key_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveApiKey(): ApiKeyEntity?

    @Query("UPDATE api_key_profiles SET isActive = 0")
    suspend fun clearActiveFlags()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApiKey(entity: ApiKeyEntity): Long

    @Query("UPDATE api_key_profiles SET isActive = 1 WHERE id = :id")
    suspend fun activateApiKey(id: Long)

    @Query("DELETE FROM api_key_profiles WHERE id = :id")
    suspend fun deleteApiKey(id: Long)

    @Transaction
    suspend fun saveAndActivateApiKey(label: String, apiKey: String) {
        clearActiveFlags()
        insertApiKey(
            ApiKeyEntity(
                label = label.ifBlank { "기본 API 키" },
                apiKey = apiKey.trim(),
                isActive = true
            )
        )
    }

    @Transaction
    suspend fun switchActiveApiKey(id: Long) {
        clearActiveFlags()
        activateApiKey(id)
    }

    // --- Cached Sessions ---
    @Query("SELECT * FROM cached_sessions ORDER BY isPinned DESC, updateTime DESC, createTime DESC")
    fun observeSessions(): Flow<List<CachedSessionEntity>>

    @Query("SELECT * FROM cached_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getCachedSession(sessionId: String): CachedSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSessions(items: List<CachedSessionEntity>)

    @Query("DELETE FROM cached_sessions WHERE sessionId = :sessionId")
    suspend fun deleteCachedSession(sessionId: String)

    @Query("UPDATE cached_sessions SET isPinned = :pinned WHERE sessionId = :sessionId")
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean)

    // --- Cached Sources ---
    @Query("SELECT * FROM cached_sources ORDER BY owner ASC, repo ASC")
    fun observeSources(): Flow<List<CachedSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(items: List<CachedSourceEntity>)

    // --- Scheduled Tasks & Prompt Templates ---
    @Query("SELECT * FROM scheduled_templates ORDER BY id DESC")
    fun observeTemplates(): Flow<List<ScheduledTemplateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(entity: ScheduledTemplateEntity): Long

    @Query("UPDATE scheduled_templates SET lastRunAt = :timestamp WHERE id = :id")
    suspend fun markTemplateExecuted(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM scheduled_templates WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    // --- App Preferences ---
    @Query("SELECT * FROM app_preferences")
    fun observePreferences(): Flow<List<AppPreferenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPreference(pref: AppPreferenceEntity)
}

/**
 * Jules 앱의 메인 Room 데이터베이스 클래스입니다.
 */
@Database(
    entities = [
        ApiKeyEntity::class,
        CachedSessionEntity::class,
        CachedSourceEntity::class,
        ScheduledTemplateEntity::class,
        AppPreferenceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class JulesDatabase : RoomDatabase() {
    abstract fun dao(): JulesDao

    companion object {
        @Volatile
        private var INSTANCE: JulesDatabase? = null

        /**
         * 싱글톤 Room 데이터베이스 인스턴스를 반환합니다.
         */
        fun getInstance(context: Context): JulesDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JulesDatabase::class.java,
                    "jules_code_app.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

