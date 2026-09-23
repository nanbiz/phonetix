package io.github.nanbiz.phonetix.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * A file the app downloads is the file it asked for, whole, or nothing at all.
 *
 * What arrives is handed to the translation engine as a model or to the core as a dictionary.
 * A download that stopped halfway, a host that answered with an error page under the file's
 * name, or bytes that are not the ones the listing was checked against are all files that
 * open as something broken - so each of them has to leave nothing behind.
 */
class PacksDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: HttpServer
    private val body = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val digest = hex(MessageDigest.getInstance("SHA-256").digest(body))

    @Before
    fun serve() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.bin") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing.bin") { exchange ->
            val page = "<html>not found</html>".toByteArray()
            exchange.sendResponseHeaders(404, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
        }
        server.createContext("/cut.bin") { exchange ->
            // Promises the whole file and sends a third of it.
            exchange.sendResponseHeaders(200, body.size.toLong())
            runCatching { exchange.responseBody.write(body, 0, body.size / 3) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun address(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun a_file_that_matches_its_checksum_is_kept() {
        val to = folder.root.resolve("model.bin")
        assertTrue(Packs.download(address("/model.bin"), to, digest))
        assertArrayEquals(body, to.readBytes())
        assertFalse("nothing partial is left beside it", folder.root.resolve("model.bin.part").exists())
    }

    @Test
    fun a_file_that_does_not_match_its_checksum_is_refused() {
        val to = folder.root.resolve("model.bin")
        assertFalse(Packs.download(address("/model.bin"), to, "0".repeat(64)))
        assertFalse("the wrong bytes are not kept", to.exists())
        assertFalse(folder.root.resolve("model.bin.part").exists())
    }

    @Test
    fun an_error_page_is_not_kept_as_the_file() {
        val to = folder.root.resolve("missing.bin")
        assertFalse(Packs.download(address("/missing.bin"), to))
        assertFalse(to.exists())
    }

    @Test
    fun a_download_cut_short_leaves_nothing() {
        val to = folder.root.resolve("cut.bin")
        assertFalse(Packs.download(address("/cut.bin"), to, digest))
        assertFalse(to.exists())
        assertFalse(folder.root.resolve("cut.bin.part").exists())
    }

    @Test
    fun a_host_that_is_not_there_is_an_answer_not_a_hang() {
        val to = folder.root.resolve("nowhere.bin")
        // A port nothing listens on: refused at once, and reported as not having arrived.
        assertFalse(Packs.download("http://127.0.0.1:9/model.bin", to))
        assertFalse(to.exists())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
