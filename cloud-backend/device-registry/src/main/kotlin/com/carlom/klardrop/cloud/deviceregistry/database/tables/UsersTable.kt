package com.carlom.klardrop.cloud.deviceregistry.database.tables

import org.jetbrains.exposed.sql.Table

object UsersTable : Table("users") {
    val id = varchar("id", 64)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}
