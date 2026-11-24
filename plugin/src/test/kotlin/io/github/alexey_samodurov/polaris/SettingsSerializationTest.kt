package io.github.alexey_samodurov.polaris

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import io.github.alexey_samodurov.polaris.settings.ConnectionSettings
import io.github.alexey_samodurov.polaris.settings.PolarisSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {
    @Test
    fun `connections round-trip and carry no secret`() {
        val state = PolarisSettingsState().apply {
            connections = mutableListOf(
                ConnectionSettings().apply {
                    id = "c1"
                    name = "local"
                    baseUrl = "https://polaris.example"
                    realm = "POLARIS"
                    clientId = "reader"
                    namedCatalogs = mutableListOf("bronze", "lakehouse")
                },
            )
        }
        val xml = JDOMUtil.write(XmlSerializer.serialize(state))
        assertTrue(xml.contains("https://polaris.example"))
        assertFalse(xml.contains("secret", ignoreCase = true))

        val restored = XmlSerializer.deserialize(JDOMUtil.load(xml), PolarisSettingsState::class.java)
        val c = restored.connections.single()
        assertTrue(c.sameAs(state.connections.single()))
        assertEquals(listOf("bronze", "lakehouse"), c.namedCatalogs)
    }
}
