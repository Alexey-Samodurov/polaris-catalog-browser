package io.github.alexey_samodurov.polaris.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.CollectionListModel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.alexey_samodurov.polaris.PolarisClients
import io.github.alexey_samodurov.polaris.client.DEFAULT_SCOPE
import io.github.alexey_samodurov.polaris.client.PolarisClient
import io.github.alexey_samodurov.polaris.client.PolarisException
import io.github.alexey_samodurov.polaris.client.normalizeBaseUrl
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ListSelectionModel

/**
 * Settings | Tools | Polaris Catalog Browser: the list of connections and the form of the selected
 * one. Secrets are written to the password safe on Apply and never read back into the form; an
 * empty secret field keeps the stored secret.
 */
class PolarisConfigurable : Configurable {
    private val listModel = CollectionListModel<ConnectionSettings>()
    private val list = JBList(listModel)

    /** Secrets typed since the last apply, by connection id. */
    private val typedSecrets = mutableMapOf<String, String>()
    private val typedS3Secrets = mutableMapOf<String, String>()
    private var current: ConnectionSettings? = null

    private val nameField = JBTextField()
    private val urlField = JBTextField().apply { emptyText.text = "https://polaris.example.com" }
    private val realmField = JBTextField().apply { emptyText.text = "optional" }
    private val scopeField = JBTextField()
    private val clientIdField = JBTextField()
    private val secretField = JBPasswordField().apply { emptyText.text = "leave empty to keep the stored secret" }
    private val catalogsField = JBTextField().apply { emptyText.text = "comma-separated, for a principal that may not list catalogs" }
    private val s3KeyField = JBTextField().apply { emptyText.text = "empty: ask Polaris for vended credentials" }
    private val s3SecretField = JBPasswordField().apply { emptyText.text = "leave empty to keep the stored key" }
    private val s3EndpointField = JBTextField().apply { emptyText.text = "empty: the endpoint Polaris returns" }
    private val s3AddressingBox = javax.swing.JComboBox(arrayOf("From Polaris", "Path-style", "Virtual-host"))
    private val s3RegionField = JBTextField().apply { emptyText.text = "optional" }
    private val testResult = JLabel(" ")
    private val testButton = JButton("Test Connection").apply { addActionListener { testConnection() } }
    private var form: JComponent? = null

    override fun getDisplayName(): String = "Polaris Catalog Browser"

    override fun createComponent(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = textListCellRenderer { it?.displayName?.ifBlank { "(new connection)" } }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                saveForm()
                load(list.selectedValue)
            }
        }
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                saveForm()
                val c = ConnectionSettings().apply { name = "Polaris" }
                listModel.add(c)
                list.selectedIndex = listModel.size - 1
            }
            .setRemoveAction {
                val index = list.selectedIndex
                if (index >= 0) {
                    current = null
                    listModel.remove(index)
                    list.selectedIndex = minOf(index, listModel.size - 1)
                    if (listModel.isEmpty) load(null)
                }
            }
            .disableUpDownActions()
            .createPanel()

        val details = panel {
            row("Name:") { cell(nameField).align(AlignX.FILL) }
            row("Server URL:") { cell(urlField).align(AlignX.FILL).comment("The server root; a pasted /api/catalog is removed") }
            row("Realm:") { cell(realmField).align(AlignX.FILL) }
            row("Client id:") { cell(clientIdField).align(AlignX.FILL) }
            row("Client secret:") { cell(secretField).align(AlignX.FILL) }
            row("Scope:") { cell(scopeField).align(AlignX.FILL).comment("PRINCIPAL_ROLE:ALL, or PRINCIPAL_ROLE:&lt;role&gt; for one role") }
            row("Named catalogs:") { cell(catalogsField).align(AlignX.FILL) }
            row { cell(testButton); cell(testResult) }
            group("Data Preview Storage") {
                row {
                    comment(
                        "Keys for reading table data from S3 when Polaris vends none (STS off). " +
                            "Prefer a read-only identity; the secret goes to the password safe.",
                    )
                }
                row("S3 access key id:") { cell(s3KeyField).align(AlignX.FILL) }
                row("S3 secret access key:") { cell(s3SecretField).align(AlignX.FILL) }
                row("S3 endpoint:") { cell(s3EndpointField).align(AlignX.FILL) }
                row("Addressing:") { cell(s3AddressingBox) }
                row("Region:") { cell(s3RegionField).align(AlignX.FILL) }
            }
        }
        form = details
        reset()
        return OnePixelSplitter(false, 0.3f).apply {
            firstComponent = decorated
            secondComponent = details
        }
    }

    private fun load(c: ConnectionSettings?) {
        current = c
        val fields = listOf(nameField, urlField, realmField, scopeField, clientIdField, catalogsField, s3KeyField, s3EndpointField, s3RegionField)
        fields.forEach { it.isEnabled = c != null }
        secretField.isEnabled = c != null
        s3SecretField.isEnabled = c != null
        s3AddressingBox.isEnabled = c != null
        testButton.isEnabled = c != null
        nameField.text = c?.name.orEmpty()
        urlField.text = c?.baseUrl.orEmpty()
        realmField.text = c?.realm.orEmpty()
        scopeField.text = c?.scope ?: DEFAULT_SCOPE
        clientIdField.text = c?.clientId.orEmpty()
        catalogsField.text = c?.namedCatalogs.orEmpty().joinToString(", ")
        secretField.text = c?.let { typedSecrets[it.id] }.orEmpty()
        s3KeyField.text = c?.s3AccessKeyId.orEmpty()
        s3SecretField.text = c?.let { typedS3Secrets[it.id] }.orEmpty()
        s3EndpointField.text = c?.s3Endpoint.orEmpty()
        s3AddressingBox.selectedIndex = when (c?.s3Addressing) {
            "PATH_STYLE" -> 1
            "VIRTUAL_HOST" -> 2
            else -> 0
        }
        s3RegionField.text = c?.s3Region.orEmpty()
        testResult.text = " "
    }

    private fun saveForm() {
        val c = current ?: return
        c.name = nameField.text.trim()
        c.baseUrl = urlField.text.trim()
        c.realm = realmField.text.trim()
        c.scope = scopeField.text.trim().ifEmpty { DEFAULT_SCOPE }
        c.clientId = clientIdField.text.trim()
        c.namedCatalogs = catalogsField.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().toMutableList()
        val secret = String(secretField.password)
        if (secret.isNotEmpty()) typedSecrets[c.id] = secret
        c.s3AccessKeyId = s3KeyField.text.trim()
        c.s3Endpoint = s3EndpointField.text.trim()
        c.s3Addressing = when (s3AddressingBox.selectedIndex) {
            1 -> "PATH_STYLE"
            2 -> "VIRTUAL_HOST"
            else -> ""
        }
        c.s3Region = s3RegionField.text.trim()
        val s3Secret = String(s3SecretField.password)
        if (s3Secret.isNotEmpty()) typedS3Secrets[c.id] = s3Secret
        list.repaint()
    }

    override fun isModified(): Boolean {
        saveForm()
        val stored = PolarisSettings.getInstance().connections
        val edited = listModel.items
        return typedSecrets.isNotEmpty() || typedS3Secrets.isNotEmpty() || stored.size != edited.size ||
            stored.zip(edited).any { (a, b) -> !a.sameAs(b) }
    }

    override fun apply() {
        saveForm()
        // Copies, taken now: the form keeps editing the list's objects, and the save below runs
        // later on another thread; it must save what was applied, not what is typed afterwards.
        val edited = listModel.items.map { it.copy() }
        for (c in edited) {
            try {
                normalizeBaseUrl(c.baseUrl)
            } catch (e: IllegalArgumentException) {
                throw ConfigurationException("${c.displayName}: ${e.message}")
            }
            if (c.clientId.isBlank()) throw ConfigurationException("${c.displayName}: the client id is empty")
        }
        val ids = edited.map { it.id }.toSet()
        val removed = PolarisSettings.getInstance().connections.map { it.id } - ids
        // A secret typed for a connection removed before Apply goes nowhere.
        val secrets = typedSecrets.filterKeys { it in ids }
        val s3Secrets = typedS3Secrets.filterKeys { it in ids }
        val clientIds = edited.associate { it.id to it.clientId }
        val s3KeyIds = edited.associate { it.id to it.s3AccessKeyId.trim() }
        typedSecrets.clear()
        typedS3Secrets.clear()
        secretField.text = ""
        s3SecretField.text = ""
        // The password safe may block on the OS keychain; write before telling listeners, off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            secrets.forEach { (id, secret) -> PolarisSecrets.set(id, clientIds[id].orEmpty(), secret) }
            s3Secrets.forEach { (id, secret) -> PolarisSecrets.setS3(id, s3KeyIds[id].orEmpty(), secret) }
            removed.forEach(PolarisSecrets::remove)
            ApplicationManager.getApplication().invokeLater {
                PolarisSettings.getInstance().replaceAll(edited, secretsChanged = secrets.keys + s3Secrets.keys)
            }
        }
    }

    override fun reset() {
        val selectedId = current?.id
        typedSecrets.clear()
        typedS3Secrets.clear()
        current = null
        listModel.replaceAll(PolarisSettings.getInstance().connections)
        val index = listModel.items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        if (listModel.isEmpty) load(null) else list.selectedIndex = index
    }

    private fun testConnection() {
        saveForm()
        val c = current?.copy() ?: return
        val typed = typedSecrets[c.id]
        val owner = form ?: return
        val result = runWithModalProgressBlocking(ModalTaskOwner.component(owner), "Testing connection to ${c.displayName}") {
            try {
                val secret = typed ?: PolarisSecrets.get(c.id, c.clientId) ?: return@runWithModalProgressBlocking "No client secret entered or stored"
                PolarisClient(PolarisClients.getInstance().connection(c) { secret }).testConnection()
                null
            } catch (e: PolarisException) {
                e.message ?: e.javaClass.simpleName
            } catch (e: IllegalArgumentException) {
                e.message ?: "invalid settings"
            }
        }
        if (result == null) {
            testResult.text = "Connected: a token was issued"
        } else {
            testResult.text = "Failed"
            Messages.showErrorDialog(owner, result, "Polaris Connection Failed")
        }
    }
}
