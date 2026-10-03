package nic.drugrepo

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import nic.drugrepo.db.AuditableRecord
import nic.drugrepo.db.AuditableRecordDatabase
import nic.drugrepo.db.AuditableRecordDatabaseFactory
import nic.drugrepo.db.AnalysisStatus
import nic.drugrepo.db.CalibrationData
import nic.drugrepo.db.CameraParameters
import nic.drugrepo.db.DeviceInformation
import nic.drugrepo.db.GpsMetadata
import nic.drugrepo.db.ImageQualityMetrics
import nic.drugrepo.db.KeySecurityLevel
import nic.drugrepo.db.PresumptiveResult
import nic.drugrepo.db.ReactionRoiColorMeasurements
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AuditableRecordDaoTest {

    private lateinit var context: Context
    private var database: AuditableRecordDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteTestState()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        deleteTestState()
    }

    @Test
    fun insertThenReadByRecordIdReturnsSameRecord() {
        database = openLedger()
        val dao = database!!.auditableRecordDao()

        val record = sampleRecord()
        runBlocking { dao.insert(record) }

        val read = runBlocking { dao.getByRecordId(record.recordId) }
        assertNotNull(read)
        assertEquals(record.recordId, read!!.recordId)
        assertEquals(record.sequenceNumber, read.sequenceNumber)
        assertEquals(record.officerBadgeId, read.officerBadgeId)
        assertEquals(record.algorithmVersion, read.algorithmVersion)
        assertEquals(record.referenceDataVersion, read.referenceDataVersion)
        assertEquals(record.ciede2000Result, read.ciede2000Result)
        assertEquals(record.presumptiveResult, read.presumptiveResult)
        assertEquals(record.deviceInformation.deviceId, read.deviceInformation.deviceId)
        assertEquals(record.gpsMetadata.available, read.gpsMetadata.available)
    }

    @Test
    fun insertThenReadBySequenceNumberReturnsSameRecord() {
        database = openLedger()
        val dao = database!!.auditableRecordDao()

        val record = sampleRecord(sequenceNumber = 5)
        runBlocking { dao.insert(record) }

        val read = runBlocking { dao.getBySequenceNumber(5) }
        assertNotNull(read)
        assertEquals(record.recordId, read!!.recordId)
    }

    @Test
    fun getAllInSequenceOrderReturnsRecordsInAscendingOrder() {
        database = openLedger()
        val dao = database!!.auditableRecordDao()

        runBlocking {
            dao.insert(sampleRecord(recordId = "a", sequenceNumber = 10))
            dao.insert(sampleRecord(recordId = "b", sequenceNumber = 1))
            dao.insert(sampleRecord(recordId = "c", sequenceNumber = 5))
        }

        val all = runBlocking { dao.getAllInSequenceOrder() }
        assertEquals(3, all.size)
        assertEquals(1, all[0].sequenceNumber)
        assertEquals(5, all[1].sequenceNumber)
        assertEquals(10, all[2].sequenceNumber)
    }

    @Test
    fun getLatestReturnsMostRecentBySequence() {
        database = openLedger()
        val dao = database!!.auditableRecordDao()

        runBlocking {
            dao.insert(sampleRecord(recordId = "x", sequenceNumber = 1))
            dao.insert(sampleRecord(recordId = "y", sequenceNumber = 100))
            dao.insert(sampleRecord(recordId = "z", sequenceNumber = 50))
        }

        val latest = runBlocking { dao.getLatest() }
        assertNotNull(latest)
        assertEquals(100, latest!!.sequenceNumber)
        assertEquals("y", latest.recordId)
    }

    @Test
    fun countReturnsNumberOfInsertedRecords() {
        database = openLedger()
        val dao = database!!.auditableRecordDao()

        assertEquals(0L, runBlocking { dao.count() })
        runBlocking {
            dao.insert(sampleRecord(recordId = "1", sequenceNumber = 1))
            dao.insert(sampleRecord(recordId = "2", sequenceNumber = 2))
        }
        assertEquals(2L, runBlocking { dao.count() })
    }

    @Test
    fun getByNonExistentRecordIdReturnsNull() {
        database = openLedger()
        val dao = database!!.auditableRecordDao()

        assertNull(runBlocking { dao.getByRecordId("does-not-exist") })
        assertNull(runBlocking { dao.getBySequenceNumber(999) })
    }

    @Test
    fun daoDoesNotExposeUpdateOrDeleteOperations() {
        // This test verifies the DAO surface is append-only as required.
        // We cannot call non-existent methods, so we verify by reflection
        // that no update/delete annotated methods exist.
        val daoClass = nic.drugrepo.db.AuditableRecordDao::class.java
        val methods = daoClass.declaredMethods

        val hasUpdateMethod = methods.any { method ->
            method.annotations.any { it.annotationClass.simpleName == "Update" }
        }
        val hasDeleteMethod = methods.any { method ->
            method.annotations.any { it.annotationClass.simpleName == "Delete" }
        }

        assertTrue("DAO must not expose @Update operations", !hasUpdateMethod)
        assertTrue("DAO must not expose @Delete operations", !hasDeleteMethod)
    }

    private fun sampleRecord(
        recordId: String = UUID.randomUUID().toString(),
        sequenceNumber: Int = 1,
    ): AuditableRecord {
        val deviceInfo = DeviceInformation(
            deviceId = UUID.randomUUID().toString(),
            imei = null,
            makeModel = "TestDevice",
            osVersion = "Android 15",
            appVersion = "0.1.0-prototype",
            keySecurityLevel = KeySecurityLevel.TEE,
            attestationLeafCertSha256 = "0123456789abcdef".repeat(4),
        )

        val gps = GpsMetadata(
            available = false,
            latitude = null,
            longitude = null,
            accuracyMeters = null,
        )

        val camera = CameraParameters(
            awbLocked = false,
            aeLocked = false,
            manualControlsSupported = false,
        )

        val quality = ImageQualityMetrics(
            laplacianVariance = "25.5000",
            glareThresholdPassed = true,
        )

        val calibration = CalibrationData(
            arucoDetected = true,
            swatchCount = 6,
            ccmApplied = true,
        )

        val roi = ReactionRoiColorMeasurements(
            rawRgb = "[0.123456,0.234567,0.345678]",
            calibratedLab = "[50.1234,10.2345,20.3456]",
        )

        return AuditableRecord(
            recordId = recordId,
            sequenceNumber = sequenceNumber,
            officerBadgeId = "OFF-001",
            timestampUtc = "2026-10-03T12:00:00.000Z",
            analysisStatus = AnalysisStatus.COMPLETED,
            reagentType = "MARQUIS",
            algorithmVersion = "1.0.0",
            referenceDataVersion = "MOCK-0",
            ciede2000Result = "2.3456",
            presumptiveResult = PresumptiveResult.NEGATIVE,
            rawImageSha256 = "a".repeat(64),
            previousRecordHash = "0".repeat(64),
            recordHash = "b".repeat(64),
            digitalSignatureEcdsa = "c".repeat(100),
            deviceInformation = deviceInfo,
            gpsMetadata = gps,
            cameraParameters = camera,
            imageQualityMetrics = quality,
            calibrationData = calibration,
            reactionRoiColorMeasurements = roi,
        )
    }

    private fun openLedger(): AuditableRecordDatabase =
        AuditableRecordDatabaseFactory.open(context, TEST_ALIAS, TEST_PREFS, TEST_DB)

    private fun deleteTestState() {
        context.deleteDatabase(TEST_DB)
        context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
            .takeIf { it.containsAlias(TEST_ALIAS) }
            ?.deleteEntry(TEST_ALIAS)
    }

    private companion object {
        const val TEST_ALIAS = "drugrepo-db-key-test-dao"
        const val TEST_PREFS = "drugrepo_key_store_test_dao"
        const val TEST_DB = "drugrepo-test-dao.db"
    }
}
