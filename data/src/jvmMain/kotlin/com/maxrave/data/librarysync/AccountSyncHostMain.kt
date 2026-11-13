package com.maxrave.data.librarysync

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.simpmusic.loginsync.AccountSyncServer
import org.simpmusic.loginsync.AccountSyncServer.Registered
import org.simpmusic.loginsync.AccountSyncWire
import java.io.File
import java.net.BindException
import java.net.InetAddress
import kotlin.system.exitProcess

private const val USAGE = """SimpMusic account sync host

  serve [--data DIR] [--port N] [--bind ADDRESS] [--name NAME] [--open-signup]
      Runs the host. Devices sign in to it from Settings > Devices > Sync account.
      --bind         address to listen on (default 0.0.0.0); give the VPN address to stay off the LAN
      --open-signup  lets a device create an account; without it, accounts are made with `user add`

  user add NAME [--data DIR]      make an account, or give an existing one a new password
  user remove NAME [--data DIR]   forget an account and its devices (its folder is left on disk)
  user list [--data DIR]          accounts and the devices signed in to them

  The password for `user add` is read from SIMPMUSIC_SYNC_PASSWORD, or asked for.
  DIR defaults to SIMPMUSIC_SYNC_DATA, or ~/.simpmusic-sync.
"""

/** The command line of the account sync host; the `syncServer` module's `main` is this and nothing else. */
fun runAccountSyncHost(args: Array<String>) {
    val options = args.toMutableList()

    fun option(name: String): String? {
        val at = options.indexOf(name)
        if (at < 0) return null
        val value = options.getOrNull(at + 1) ?: fail("$name needs a value")
        options.removeAt(at + 1)
        options.removeAt(at)
        return value
    }

    fun flag(name: String): Boolean = options.remove(name)

    val data = File(option("--data") ?: System.getenv("SIMPMUSIC_SYNC_DATA") ?: (System.getProperty("user.home") + File.separator + ".simpmusic-sync"))
    when (options.removeFirstOrNull()) {
        "serve" -> {
            val port = option("--port")?.let { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } ?: fail("--port must be 1-65535") } ?: AccountSyncWire.PORT
            val bind = option("--bind") ?: "0.0.0.0"
            val name = option("--name") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("SimpMusic sync")
            val signup = flag("--open-signup")
            if (options.isNotEmpty()) fail("unknown: ${options.joinToString(" ")}")
            val host = AccountSyncHost(data, name, signup)
            runBlocking {
                try {
                    AccountSyncServer(host, port, bind).start()
                } catch (e: BindException) {
                    fail("cannot listen on $bind:$port: ${e.message}")
                }
                println("SimpMusic account sync host \"$name\" on $bind:$port, data in ${data.absolutePath}")
                println(if (signup) "Devices may create accounts." else "Accounts: ${host.users().keys.sorted().joinToString().ifEmpty { "none yet, make one with `user add NAME`" }}")
                awaitCancellation()
            }
        }

        "user" -> {
            val host = AccountSyncHost(data, name = "", signup = false)
            when (options.removeFirstOrNull()) {
                "add" -> {
                    val user = options.removeFirstOrNull() ?: fail("user add needs a name")
                    val password =
                        System.getenv("SIMPMUSIC_SYNC_PASSWORD")
                            ?: System.console()?.readPassword("Password for %s: ", user)?.concatToString()
                            ?: readlnOrNull()
                            ?: fail("no password given")
                    when (host.setUser(user, password)) {
                        Registered.OK -> println("Account \"$user\" is ready.")
                        else -> fail("the name takes letters, digits, '.', '_' and '-'; the password at least 8 characters")
                    }
                }

                "remove" -> {
                    val user = options.removeFirstOrNull() ?: fail("user remove needs a name")
                    println(if (host.removeUser(user)) "Account \"$user\" removed." else "No account \"$user\".")
                }

                "list" -> {
                    host.users().toSortedMap().forEach { (user, devices) ->
                        println(user)
                        devices.forEach { println("  ${it.name} (${it.os})${if (it.lastSyncAt == null) ", never synced" else ""}") }
                    }
                }

                else -> fail(USAGE)
            }
        }

        else -> {
            print(USAGE)
        }
    }
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
