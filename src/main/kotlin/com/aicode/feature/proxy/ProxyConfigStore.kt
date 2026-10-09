package com.aicode.feature.proxy

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class NamedProxy(val name: String, val url: String)

class ProxyConfigStore(private val configPath: Path = defaultConfigPath()) {
    fun read(): List<NamedProxy> {
        if (Files.notExists(configPath)) {
            Files.createDirectories(configPath.parent)
            Files.writeString(configPath, DEFAULT_CONFIG, StandardCharsets.UTF_8)
        }
        try {
            val root = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8))
            require(root.isJsonObject) { "Proxy configuration must be a JSON object." }
            val obj = root.asJsonObject
            val values = obj.get("proxies")?.let { field ->
                require(field.isJsonArray) { "Proxy configuration field 'proxies' must be an array." }
                field.asJsonArray.mapIndexed { index, element ->
                    when {
                        element.isJsonPrimitive && element.asJsonPrimitive.isString -> NamedProxy(element.asString.trim(), element.asString)
                        element.isJsonObject -> {
                            val entry = element.asJsonObject
                            val name = entry.get("name")
                            val url = entry.get("url")
                            require(name?.isJsonPrimitive == true && name.asJsonPrimitive.isString) { "Proxy #${index + 1} must have a string name." }
                            require(url?.isJsonPrimitive == true && url.asJsonPrimitive.isString) { "Proxy #${index + 1} must have a string URL." }
                            NamedProxy(name.asString, url.asString)
                        }
                        else -> error("Proxy #${index + 1} must be a string or object.")
                    }
                }
            } ?: obj.get("proxy")?.let { legacy ->
                require(legacy.isJsonPrimitive && legacy.asJsonPrimitive.isString) { "Proxy configuration field 'proxy' must be a string." }
                listOf(NamedProxy(legacy.asString, legacy.asString))
            } ?: emptyList()
            return values.map { proxy ->
                val name = proxy.name.trim()
                require(name.isNotEmpty()) { "Proxy name must not be empty." }
                val url = normalizeUrl(proxy.url)
                NamedProxy(if (proxy.name == proxy.url) url else name, url)
            }
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("Invalid proxy JSON in $configPath: ${e.message}", e)
        } catch (e: IllegalArgumentException) { throw e }
        catch (e: Exception) { throw IllegalStateException("Could not read proxy configuration $configPath: ${e.message}", e) }
    }

    companion object {
        const val DEFAULT_CONFIG = "{\n  \"proxies\": [\n    { \"name\": \"Local\", \"url\": \"http://127.0.0.1:1087\" }\n  ]\n}\n"
        fun defaultConfigPath(): Path = Path.of(System.getProperty("user.home"), ".project-atlas", "proxy.json")

        fun normalizeUrl(raw: String): String {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Proxy URL must not be empty." }
            val uri = try { URI(value) } catch (e: Exception) { throw IllegalArgumentException("Invalid proxy URL.", e) }
            require(uri.scheme.equals("http", true)) { "Proxy URL must use http://. HTTPS proxies are not supported." }
            require(!uri.host.isNullOrBlank()) { "Proxy URL must include a valid host." }
            require(uri.rawUserInfo == null) { "Proxy URL must not include credentials; IDEA proxy authentication is not configured by Project Atlas." }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Proxy URL must not include a path." }
            require(uri.rawQuery == null && uri.rawFragment == null) { "Proxy URL must not include a query or fragment." }
            require(uri.port == -1 || uri.port in 1..65535) { "Proxy port must be between 1 and 65535." }
            return value.removeSuffix("/")
        }
    }
}
