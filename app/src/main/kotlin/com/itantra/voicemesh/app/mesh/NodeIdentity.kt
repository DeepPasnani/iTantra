package com.itantra.voicemesh.app.mesh

import android.content.Context
import com.itantra.voicemesh.messaging.NodeId
import kotlin.random.Random

/**
 * A stable [NodeId] for this install, generated locally (spec §5: never derived from
 * a hardware identifier) and persisted so it survives app restarts — restarting with a
 * new id on every launch would break dedup/ACK matching for anything still in-flight.
 */
object NodeIdentity {
    private const val PREFS_NAME = "voicemesh_identity"
    private const val KEY_NODE_ID = "node_id"

    fun getOrCreate(context: Context): NodeId {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getInt(KEY_NODE_ID, 0)
        if (existing != 0) return NodeId(existing.toUInt())

        var generated: Int
        do {
            generated = Random.nextInt()
        } while (generated == 0 || generated == -1) // avoid UNKNOWN(0) and BROADCAST(0xFFFFFFFF)

        prefs.edit().putInt(KEY_NODE_ID, generated).apply()
        return NodeId(generated.toUInt())
    }
}
