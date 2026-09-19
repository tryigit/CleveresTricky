package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxPriorityOrderTest {

    @Test
    fun `category mapping resolves all valid security levels`() {
        assertEquals(
            KeyboxPriorityCategory.VALID_RKP,
            KeyboxPriorityCategory.fromValidityAndLevel(KeyboxVerifier.ValidityState.VALID, null, "RKP"),
        )
        assertEquals(
            KeyboxPriorityCategory.VALID_STRONGBOX,
            KeyboxPriorityCategory.fromValidityAndLevel(KeyboxVerifier.ValidityState.VALID, null, "StrongBox"),
        )
        assertEquals(
            KeyboxPriorityCategory.VALID_TEE,
            KeyboxPriorityCategory.fromValidityAndLevel(KeyboxVerifier.ValidityState.VALID, null, "TEE"),
        )
        assertEquals(
            KeyboxPriorityCategory.VALID_UNKNOWN,
            KeyboxPriorityCategory.fromValidityAndLevel(KeyboxVerifier.ValidityState.VALID, null, "Unknown"),
        )
    }

    @Test
    fun `category mapping resolves invalid sub-reasons accurately`() {
        assertEquals(
            KeyboxPriorityCategory.INVALID_EXPIRED_RKP,
            KeyboxPriorityCategory.fromValidityAndLevel(
                KeyboxVerifier.ValidityState.INVALID,
                KeyboxVerifier.InvalidReason.EXPIRED,
                "RKP",
            ),
        )
        assertEquals(
            KeyboxPriorityCategory.INVALID_REVOKED_STRONGBOX,
            KeyboxPriorityCategory.fromValidityAndLevel(
                KeyboxVerifier.ValidityState.INVALID,
                KeyboxVerifier.InvalidReason.REVOKED,
                "StrongBox",
            ),
        )
        assertEquals(
            KeyboxPriorityCategory.INVALID_VERIFICATION_FAILED_TEE,
            KeyboxPriorityCategory.fromValidityAndLevel(
                KeyboxVerifier.ValidityState.INVALID,
                KeyboxVerifier.InvalidReason.VERIFICATION_FAILED,
                "TEE",
            ),
        )
        assertEquals(
            KeyboxPriorityCategory.INVALID_VERIFICATION_FAILED_UNKNOWN,
            KeyboxPriorityCategory.fromValidityAndLevel(
                KeyboxVerifier.ValidityState.INVALID,
                null,
                "other",
            ),
        )
    }

    @Test
    fun `default preference serialization roundtrip`() {
        val pref = KeyboxPriorityPreference.DEFAULT
        val json = pref.toJson()
        assertEquals("default", json.getString("mode"))

        val restored = KeyboxPriorityPreference.fromJson(json)
        assertEquals(KeyboxPriorityPreference.Mode.DEFAULT, restored.mode)
        assertEquals(KeyboxPriorityCategory.DEFAULT_ORDER, restored.effectiveOrder())
    }

    @Test
    fun `custom preference serialization roundtrip`() {
        val customOrder = KeyboxPriorityCategory.DEFAULT_ORDER.reversed()
        val pref = KeyboxPriorityPreference(KeyboxPriorityPreference.Mode.CUSTOM, customOrder)
        val json = pref.toJson()
        assertEquals("custom", json.getString("mode"))

        val restored = KeyboxPriorityPreference.fromJson(json)
        assertEquals(KeyboxPriorityPreference.Mode.CUSTOM, restored.mode)
        assertEquals(customOrder, restored.effectiveOrder())
    }

    @Test
    fun `custom UI six-permutation is accepted and expanded deterministically`() {
        val uiOrder = KeyboxPriorityCategory.UI_ORDER
        assertEquals(6, uiOrder.size)
        val submitted = uiOrder.reversed()
        val json = JSONObject().apply {
            put("mode", "custom")
            put("customOrder", JSONArray(submitted.map { it.name }))
        }
        val restored = KeyboxPriorityPreference.fromJson(json)
        assertEquals(KeyboxPriorityPreference.Mode.CUSTOM, restored.mode)
        assertEquals(submitted, restored.customOrder)

        val effective = restored.effectiveOrder()
        assertEquals(16, effective.size)
        assertEquals(submitted, effective.take(6))
        assertEquals(
            KeyboxPriorityCategory.DEFAULT_ORDER.filter { it !in submitted },
            effective.drop(6),
        )
    }

    @Test
    fun `custom preference falls back to default on incomplete order`() {
        val incompleteJson = JSONObject().apply {
            put("mode", "custom")
            put("customOrder", JSONArray(KeyboxPriorityCategory.DEFAULT_ORDER.dropLast(1).map { it.name }))
        }
        val restored = KeyboxPriorityPreference.fromJson(incompleteJson)
        assertEquals(KeyboxPriorityPreference.DEFAULT, restored)
    }

    @Test
    fun `custom preference falls back to default on unknown category`() {
        val unknownOrder = KeyboxPriorityCategory.DEFAULT_ORDER.map { it.name }.toMutableList()
        unknownOrder[unknownOrder.lastIndex] = "VALID_FUTURE_CATEGORY"
        val unknownJson = JSONObject().apply {
            put("mode", "custom")
            put("customOrder", JSONArray(unknownOrder))
        }
        val restored = KeyboxPriorityPreference.fromJson(unknownJson)
        assertEquals(KeyboxPriorityPreference.DEFAULT, restored)
    }

    @Test
    fun `custom preference falls back to default on duplicate category`() {
        val duplicateOrder = KeyboxPriorityCategory.DEFAULT_ORDER.map { it.name }.toMutableList()
        duplicateOrder[duplicateOrder.lastIndex] = duplicateOrder.first()
        val duplicateJson = JSONObject().apply {
            put("mode", "custom")
            put("customOrder", JSONArray(duplicateOrder))
        }
        val restored = KeyboxPriorityPreference.fromJson(duplicateJson)
        assertEquals(KeyboxPriorityPreference.DEFAULT, restored)
    }

    @Test
    fun `null or empty json falls back to default`() {
        val restoredNull = KeyboxPriorityPreference.fromJson(null)
        assertEquals(KeyboxPriorityPreference.DEFAULT, restoredNull)

        val restoredEmpty = KeyboxPriorityPreference.fromJson(JSONObject())
        assertEquals(KeyboxPriorityPreference.DEFAULT, restoredEmpty)
    }

    @Test
    fun `filterTopPriorityTier returns only highest available priority category`() {
        val kbRkp = MockKeyBox("rkp", "RKP")
        val kbTee = MockKeyBox("tee", "TEE")
        val kbSb = MockKeyBox("sb", "StrongBox")

        val pool = listOf(kbTee, kbRkp, kbSb)
        val filtered = KeyboxPriorityOrder.filterTopPriorityTier(
            pool,
            KeyboxPriorityCategory.DEFAULT_ORDER,
        ) { kb ->
            when (kb.id) {
                "rkp" -> KeyboxPriorityCategory.VALID_RKP
                "sb" -> KeyboxPriorityCategory.VALID_STRONGBOX
                else -> KeyboxPriorityCategory.VALID_TEE
            }
        }

        // VALID_RKP is higher than STRONGBOX and TEE in default order
        assertEquals(1, filtered.size)
        assertEquals("rkp", filtered[0].id)
    }

    @Test
    fun `filterTopPriorityTier keeps all items in same top tier`() {
        val kbRkp1 = MockKeyBox("rkp1", "RKP")
        val kbRkp2 = MockKeyBox("rkp2", "RKP")
        val kbTee = MockKeyBox("tee", "TEE")

        val pool = listOf(kbRkp1, kbTee, kbRkp2)
        val filtered = KeyboxPriorityOrder.filterTopPriorityTier(
            pool,
            KeyboxPriorityCategory.DEFAULT_ORDER,
        ) { kb ->
            if (kb.id.startsWith("rkp")) KeyboxPriorityCategory.VALID_RKP
            else KeyboxPriorityCategory.VALID_TEE
        }

        assertEquals(2, filtered.size)
        assertTrue(filtered.contains(kbRkp1))
        assertTrue(filtered.contains(kbRkp2))
    }

    private data class MockKeyBox(val id: String, val level: String)
}
