package com.carlom.klardrop.cloud.deviceregistry.database

import com.carlom.klardrop.cloud.deviceregistry.config.DatabaseConfig
import com.carlom.klardrop.cloud.deviceregistry.database.tables.AuditEventsTable
import com.carlom.klardrop.cloud.deviceregistry.database.tables.DevicesTable
import com.carlom.klardrop.cloud.deviceregistry.database.tables.UsersTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    private val logger = KotlinLogging.logger {}

    @Volatile
    var connected: Boolean = false
        private set

    private var dataSource: HikariDataSource? = null

    fun init(config: DatabaseConfig) {
        if (config.url.isBlank()) {
            logger.warn { "DATABASE_URL not configured; running with in-memory repositories." }
            connected = false
            return
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = System.getenv("DATABASE_USER")
            password = System.getenv("DATABASE_PASSWORD")
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource!!)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(UsersTable, DevicesTable, AuditEventsTable)
        }

        connected = true
        logger.info { "Database initialized and schemas verified." }
    }

    fun close() {
        runCatching { dataSource?.close() }
        connected = false
    }
}
