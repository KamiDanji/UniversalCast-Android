package com.universalcast.cli

import com.universalcast.playback.PlaybackStatus
import com.universalcast.playback.RemoteKey
import com.universalcast.security.SoftwareSenderIdentity
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.FileMediaSource
import com.universalcast.sender.MediaProxyServer
import com.universalcast.sender.PairOutcome
import com.universalcast.sender.PairedReceiver
import com.universalcast.sender.PairedReceiverJson
import com.universalcast.sender.PairingSession
import com.universalcast.sender.SenderController
import java.io.File

/**
 * Pure-JVM Desktop / CLI Sender for UniversalCast Protocol 1.0.
 *
 * Commands:
 * - `pair <ucp://pair?d=...>`         Pair with a TV using its QR URI
 * - `cast <http(s)://...>`            Cast a direct media or HLS/DASH stream URL
 * - `cast-file <path/to/file.mp4>`    Stream a local file from this computer via [MediaProxyServer]
 * - `key <UP|DOWN|LEFT|RIGHT|CENTER|BACK|HOME|PLAY_PAUSE|MUTE|VOLUME_UP|VOLUME_DOWN>`
 * - `text <message>`                  Type text onto the TV
 * - `status`                          Print current TV playback snapshot
 * - `stop`                            Stop TV playback
 */
object UniversalCastCli {
    private val configDir = File(System.getProperty("user.home"), ".universalcast")
    private val receiverFile = File(configDir, "paired_tv.json")

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            printUsage()
            return
        }
        val identity = SoftwareSenderIdentity.generate()
        when (val cmd = args[0].lowercase()) {
            "pair" -> {
                val qr = args.getOrNull(1) ?: run { println("Usage: pair <ucp://pair?d=...>"); return }
                val ps = PairingSession(identity, "Desktop CLI", qr)
                val sas = ps.begin()
                println("Compare 6-digit code with TV: $sas")
                ps.confirm()
                println("Waiting for approval on TV...")
                when (val out = ps.awaitApproval(120_000)) {
                    is PairOutcome.Approved -> {
                        configDir.mkdirs()
                        receiverFile.writeText(PairedReceiverJson.encode(out.receiver), Charsets.UTF_8)
                        println("Paired with ${out.receiver.name} as ${out.receiver.role}")
                    }
                    else -> println("Pairing did not complete: $out")
                }
            }
            "cast", "cast-file", "key", "text", "status", "stop" -> {
                if (!receiverFile.exists()) {
                    println("No paired TV found in ${receiverFile.absolutePath}. Run 'pair <qr-uri>' first.")
                    return
                }
                val receiver = PairedReceiverJson.decode(receiverFile.readText(Charsets.UTF_8)) ?: run {
                    println("Corrupt paired_tv.json.")
                    return
                }
                runWithReceiver(identity, receiver, cmd, args.drop(1))
            }
            else -> printUsage()
        }
    }

    private fun runWithReceiver(
        identity: SoftwareSenderIdentity,
        receiver: PairedReceiver,
        cmd: String,
        rest: List<String>,
    ) {
        val ctl = SenderController(identity)
        val proxy = MediaProxyServer()
        try {
            ctl.connect(receiver)
            val deadline = System.currentTimeMillis() + 6_000L
            while (ctl.state.phase != ConnectionPhase.CONNECTED && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (ctl.state.phase != ConnectionPhase.CONNECTED) {
                println("Failed to connect to ${receiver.name}: ${ctl.state.message}")
                return
            }
            when (cmd) {
                "cast" -> {
                    val url = rest.firstOrNull() ?: run { println("Usage: cast <url> [title]"); return }
                    ctl.load(url, rest.getOrNull(1))
                    Thread.sleep(500)
                    println("Casting $url to ${receiver.name}")
                }
                "cast-file" -> {
                    val path = rest.firstOrNull() ?: run { println("Usage: cast-file <file>"); return }
                    val file = File(path)
                    if (!file.isFile) { println("File not found: $path"); return }
                    val cast = proxy.registerLocal(FileMediaSource(file), targetTvHost = receiver.addresses.firstOrNull())
                    ctl.load(cast)
                    println("Streaming ${file.name} via local proxy (${cast.uri}). Press Ctrl+C to stop.")
                    while (ctl.state.phase == ConnectionPhase.CONNECTED && ctl.state.snapshot.status != PlaybackStatus.ENDED) {
                        Thread.sleep(1_000)
                    }
                }
                "key" -> {
                    val keyName = rest.firstOrNull() ?: return
                    val key = RemoteKey.fromWire(keyName) ?: run { println("Unknown key: $keyName"); return }
                    ctl.remoteKey(key)
                    Thread.sleep(300)
                }
                "text" -> {
                    val text = rest.joinToString(" ")
                    ctl.remoteText(text, submit = true)
                    Thread.sleep(300)
                }
                "stop" -> {
                    ctl.stop()
                    Thread.sleep(300)
                }
                "status" -> {
                    val s = ctl.state.snapshot
                    println("${receiver.name}: ${s.status} · ${s.title ?: s.mediaUri ?: "idle"} (${s.positionMs / 1000}s)")
                }
            }
        } finally {
            proxy.stop()
            ctl.shutdown()
        }
    }

    private fun printUsage() {
        println(
            """
            UniversalCast CLI (UCP 1.0)
            Usage:
              pair <ucp://pair?d=...>
              cast <http(s)://...> [title]
              cast-file <path/to/media>
              key <UP|DOWN|LEFT|RIGHT|CENTER|BACK|HOME|PLAY_PAUSE|MUTE|VOLUME_UP|VOLUME_DOWN>
              text <message>
              status
              stop
            """.trimIndent()
        )
    }
}
