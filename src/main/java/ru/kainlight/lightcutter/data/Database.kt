package ru.kainlight.lightcutter.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ru.kainlight.lightcutter.Debug
import ru.kainlight.lightcutter.Main
import ru.kainlight.lightcutter.api.IRegion
import ru.kainlight.lightcutter.api.Region
import ru.kainlight.lightcutter.api.RegionHandler
import java.io.File
import java.io.IOException
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

@Suppress("UNUSED")
internal class Database(private val plugin: Main) : RegionHandler {

    private val host: String = plugin.config.getString("database-settings.host", "localhost")!!
    private val port: Int = plugin.config.getInt("database-settings.port", 3306)
    private val base: String = plugin.config.getString("database-settings.base", "lightcutter")!!

    private var dataSource: HikariDataSource? = null
    private val hikariConfig: HikariConfig = HikariConfig()

    private val columnName = "lightcutter_regions"

    private val cache: ConcurrentHashMap<String, Region> = ConcurrentHashMap()
    private val caching: Boolean = plugin.config.getBoolean("database-settings.caching", false)

    private fun configureDataSource(driverClassName: String, jdbcUrl: String, sqlite: Boolean = false) {
        hikariConfig.driverClassName = driverClassName
        hikariConfig.jdbcUrl = if (sqlite) "jdbc:sqlite://$jdbcUrl" else "$jdbcUrl$host:$port/$base"
        hikariConfig.username = plugin.config.getString("database-settings.user", "root")!!
        hikariConfig.password = plugin.config.getString("database-settings.password", "")!!
        hikariConfig.maximumPoolSize = plugin.config.getInt("database-settings.pool-size", 2)
        hikariConfig.poolName = "LightCutter-Pool"

        dataSource = HikariDataSource(hikariConfig)
    }

    fun connect() {
        when (plugin.config.getString("database-settings.storage", "sqlite")!!.lowercase()) {
            "mysql" -> configureDataSource("com.mysql.cj.jdbc.Driver", "jdbc:mysql://")
            "mariadb" -> configureDataSource("org.mariadb.jdbc.Driver", "jdbc:mariadb://")
            "postgresql" -> configureDataSource("org.postgresql.Driver", "jdbc:postgresql://")
            "sqlite" -> {
                val dbFile = File(plugin.dataFolder, "$base.db")
                if (!dbFile.exists()) {
                    try {
                        dbFile.createNewFile()
                    } catch (e: IOException) {
                        Debug.error(e.message.toString(), e)
                    }
                }
                configureDataSource("org.sqlite.JDBC", dbFile.absolutePath, true)
            }
        }
    }

    fun disconnect() {
        try {
            if (isConnected()) dataSource?.close()
        } catch (e: Exception) {
            Debug.error(e.message.toString(), e)
        }
    }

    private fun isConnected(): Boolean = dataSource != null && !(dataSource?.isClosed ?: true)

    fun createTables() {
        executeUpdate(
            "CREATE TABLE IF NOT EXISTS $columnName (region_name VARCHAR(64) PRIMARY KEY, earn VARCHAR(16), need_break INT UNSIGNED, cooldown INT UNSIGNED)"
        )
    }

    override fun addRegion(name: String, earn: String, needBreak: Int, cooldown: Int): Region? {
        val rowsAffected = executeUpdate(
            """
            INSERT INTO $columnName (region_name, earn, need_break, cooldown) 
            SELECT ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM $columnName WHERE region_name = ?)
            """
        ) {
            it.setString(1, name)
            it.setString(2, earn)
            it.setInt(3, needBreak)
            it.setInt(4, cooldown)
            it.setString(5, name)
        }
        val region = IRegion(name, earn, needBreak, cooldown)

        return if (rowsAffected > 0) {
            if (caching) cache[name] = region
            region
        } else null
    }

    override fun removeRegion(name: String): Int {
        val rowsAffected = executeUpdate("DELETE FROM $columnName WHERE region_name = ?") {
            it.setString(1, name)
        }
        if (caching && rowsAffected > 0) cache.remove(name)
        return rowsAffected
    }

    override fun updateRegion(region: Region): Int {
        val rowsAffected = executeUpdate(
            "UPDATE $columnName SET earn = ?, need_break = ?, cooldown = ? WHERE region_name = ?"
        ) {
            it.setString(1, region.earn)
            it.setInt(2, region.needBreak)
            it.setInt(3, region.cooldown)
            it.setString(4, region.name)
        }
        if (caching && rowsAffected > 0) cache[region.name] = region
        return rowsAffected
    }

    override fun hasRegion(name: String): Boolean {
        return if (caching) {
            getRegion(name) != null
        } else {
            executeQuery(
                "SELECT 1 FROM $columnName WHERE region_name = ?",
                { it.setString(1, name) }
            ) { true } != null
        }
    }

    override fun getRegion(name: String): Region? {
        return if (caching) {
            cache[name] ?: fetchRegionFromDatabase(name)?.also { cache[name] = it }
        } else {
            fetchRegionFromDatabase(name)
        }
    }

    override fun getRegions(): List<Region> {
        return if (caching) cache.values.toList() else fetchAllRegionsFromDatabase()
    }

    internal fun initializeCache() {
        if (caching) {
            fetchAllRegionsFromDatabase().forEach { region ->
                cache[region.name] = region
            }
        }
    }

    private fun fetchRegionFromDatabase(name: String): Region? {
        return executeQuery(
            "SELECT * FROM $columnName WHERE region_name = ?",
            { it.setString(1, name) }
        ) { rs ->
            IRegion(
                name = rs.getString("region_name"),
                earn = rs.getString("earn"),
                needBreak = rs.getInt("need_break"),
                cooldown = rs.getInt("cooldown")
            )
        }
    }

    private fun fetchAllRegionsFromDatabase(): List<Region> {
        return executeQuery("SELECT * FROM $columnName", mapper = { rs ->
            mutableListOf<IRegion>().apply {
                do {
                    add(
                        IRegion(
                            name = rs.getString("region_name"),
                            earn = rs.getString("earn"),
                            needBreak = rs.getInt("need_break"),
                            cooldown = rs.getInt("cooldown")
                        )
                    )
                } while (rs.next())
            }
        }) ?: emptyList()
    }

    private fun <T> executeQuery(
        sql: String,
        setter: (PreparedStatement) -> Unit = {},
        mapper: (ResultSet) -> T?
    ): T? {
        try {
            dataSource?.connection?.use { connection ->
                connection.prepareStatement(sql).use { stmt ->
                    setter(stmt)
                    stmt.executeQuery().use { rs ->
                        return if (rs.next()) mapper(rs) else null
                    }
                }
            }
        } catch (e: Exception) {
            Debug.error("Database query error: ${e.message}", e)
        }
        return null
    }

    private fun executeUpdate(sql: String, setter: (PreparedStatement) -> Unit = {}): Int {
        try {
            dataSource?.connection?.use { connection ->
                connection.prepareStatement(sql).use { stmt ->
                    setter(stmt)
                    return stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            Debug.error("Database update error: ${e.message}", e)
        }
        return 0
    }
}
