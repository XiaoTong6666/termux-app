package com.termux.filepicker

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.xh_lib.utils.LogUtils
import com.example.xh_lib.utils.UUtils
import com.termux.R
import com.termux.app.TermuxInstaller
import com.termux.app.TermuxService
import com.termux.shared.android.PackageUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.net.uri.UriScheme
import com.termux.shared.net.uri.UriUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_APP
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.shared.termux.interact.TextInputDialogUtils
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties
import com.termux.zerocore.utils.FileIOUtils
import com.zp.z_file.bean.DataBean
import com.zp.z_file.ui.dialog.InstallModuleDialog
import com.zp.z_file.ui.dialog.LoadingDialog
import com.zp.z_file.ui.dialog.SwitchDialog
import com.zp.z_file.util.InstallTarData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern


class TermuxFileReceiverActivity : AppCompatActivity() {
    private val TAG = "TermuxFileReceiverActivity"
    private var finishOnDismissNameDialog = true
    private val start_fz: LinearLayout by lazy { findViewById(R.id.start_fz) }
    private val install_module: LinearLayout by lazy { findViewById(R.id.install_module) }
    private val install_data: LinearLayout by lazy { findViewById(R.id.install_data) }
    private val msg_file: TextView by lazy { findViewById(R.id.msg_file) }
    private val msg_pro: TextView by lazy { findViewById(R.id.msg_pro) }
    private val image_view: ImageView by lazy { findViewById(R.id.image_view) }
    private val pro: ProgressBar by lazy { findViewById(R.id.pro) }
    private var mFile: File? = null
    private var incomingFileName: String? = null

    companion object {
        private const val LOG_TAG = "TermuxFileReceiverActivity"
        private const val TERMUX_RECEIVEDIR = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads"
        private const val EDITOR_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor"
        private const val URL_OPENER_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener"
        private val WEB_URL_PATTERN = Pattern.compile("^(?i)(https?|ftp)://\\S+$")

        @JvmStatic
        fun isSharedTextAnUrl(sharedText: String?): Boolean {
            if (sharedText.isNullOrEmpty()) return false
            return WEB_URL_PATTERN.matcher(sharedText).matches() || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText)
        }

        @JvmStatic
        fun updateReceiverComponentsState(context: Context) {
            Thread {
                val properties = TermuxAppSharedProperties.getProperties() ?: return@Thread

                var errorMessage: String?

                val shareState = !properties.isFileShareReceiverDisabled()
                Logger.logVerbose(LOG_TAG, "Setting ${TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state to $shareState")
                errorMessage = PackageUtils.setComponentState(
                    context,
                    TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                    shareState,
                    null,
                    false,
                    false
                )
                if (errorMessage != null) {
                    Logger.logError(LOG_TAG, errorMessage)
                }

                val viewState = !properties.isFileViewReceiverDisabled()
                Logger.logVerbose(LOG_TAG, "Setting ${TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state to $viewState")
                errorMessage = PackageUtils.setComponentState(
                    context,
                    TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                    viewState,
                    null,
                    false,
                    false
                )
                if (errorMessage != null) {
                    Logger.logError(LOG_TAG, errorMessage)
                }
            }.start()
        }
    }

    private fun resolveIncomingUri(): Uri? {
        val intent = intent ?: return null
        return if (Intent.ACTION_SEND == intent.action) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data
        } else {
            intent.data
        }
    }

    private fun resolveIncomingDisplayName(uri: Uri?): String? {
        if (uri == null) return null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val fileNameColumnId = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (fileNameColumnId >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(fileNameColumnId)
                }
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "resolveIncomingDisplayName error: $e")
        }
        return uri.lastPathSegment
    }

    private fun resolveEffectiveFileName(displayName: String?, file: File): String {
        val normalizedDisplayName = displayName?.let { File(it).name }?.takeIf { it.isNotBlank() }
        return normalizedDisplayName ?: file.name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_termux_file_receiver)

        val sendAction = intent?.action
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (Intent.ACTION_SEND == sendAction && sharedText != null && resolveIncomingUri() == null) {
            val sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null)
            val subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, sharedTitle)
            if (isSharedTextAnUrl(sharedText)) {
                handleUrlAndFinish(sharedText)
            } else {
                val suggestedName = subject?.let { "$it.txt" }
                promptNameAndSave(ByteArrayInputStream(sharedText.toByteArray(StandardCharsets.UTF_8)), suggestedName)
            }
            return
        }

        try {
            val incomingUri = resolveIncomingUri()
            if (incomingUri == null) {
                showErrorDialogAndQuit("Data uri not passed.")
                return
            }
            val realPathFromURI = UUtils.getFileAbsolutePath(this, incomingUri)
            if (realPathFromURI.isNullOrEmpty()) {
                UUtils.showMsg(UUtils.getString(R.string.copy_uri_error))
                finish()
                return
            }
            realPathFromURI.let {
                mFile = File(it)
                incomingFileName = resolveEffectiveFileName(resolveIncomingDisplayName(incomingUri), mFile!!)
                if (FileIOUtils.isPacketFormat(mFile!!.name)) {
                    install_data.visibility = View.VISIBLE
                } else {
                    install_data.visibility = View.GONE
                }

                if (FileIOUtils.isModuleFormat(mFile!!.name)) {
                    install_module.visibility = View.VISIBLE
                } else {
                    install_module.visibility = View.GONE
                }
                install_data.setOnClickListener {
                    val loadingDialog = LoadingDialog(this)
                    loadingDialog.show()
                    TermuxInstaller.setupStorageSymlinks(this)
                    GlobalScope.launch(Dispatchers.IO) {
                        delay(3000)
                        withContext(Dispatchers.Main) {
                            InstallTarData.installTar(this@TermuxFileReceiverActivity, realPathFromURI)
                            loadingDialog.dismiss()
                        }
                    }
                }
                install_module.setOnClickListener {
                    val switchDialog = SwitchDialog(this)
                    switchDialog.createSwitchDialog(UUtils.getString(R.string.termux_install_module_switch))
                    switchDialog.ok?.setOnClickListener {
                        switchDialog.dismiss()
                        val installModuleDialog = InstallModuleDialog(this)
                        installModuleDialog.show()
                        installModuleDialog.setCancelable(false)
                        val dataBean = DataBean()
                        dataBean.mFile = mFile
                        installModuleDialog.installModule(dataBean)
                    }
                    switchDialog.show()
                }
                LogUtils.d(TAG, "onCreate file Size: ${mFile?.length()}")
                LogUtils.d(TAG, "onCreate file Path: ${mFile?.absolutePath}")
                val lengthToMb = FileIOUtils.getLengthToMb(mFile!!)
                if (lengthToMb != null) {
                    msg_file.text = UUtils.getString(R.string.file_name_copy)
                        .replace("{file}", incomingFileName ?: mFile!!.name)
                        .replace("{size}", lengthToMb)
                        .replace("{path}", mFile!!.absolutePath)
                        .replace("{suffix}", FileIOUtils.getExtension(mFile!!))
                } else {
                    msg_file.text = UUtils.getString(R.string.file_name_copy)
                        .replace("{file}", incomingFileName ?: "N/A")
                        .replace("{size}", "N/A")
                        .replace("{path}", "N/A")
                        .replace("{suffix}", "N/A")
                }
            }

            start_fz.setOnClickListener {
                if (mFile == null) {
                    UUtils.showMsg(UUtils.getString(R.string.not_file_msg))
                    finish()
                    return@setOnClickListener
                }
                val file = File(FileIOUtils.getHomePath(UUtils.getContext()), incomingFileName ?: mFile!!.name)
                LogUtils.d(TAG, "onCreate file: ${file.absolutePath}")
                if (!file.exists()) {
                    file.createNewFile()
                }
                try {
                    val fileInputStream = FileInputStream(mFile!!)
                    image_view.visibility = View.GONE
                    pro.visibility = View.VISIBLE
                    GlobalScope.launch(Dispatchers.IO) {
                        showText(file, fileInputStream)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    UUtils.showMsg(UUtils.getString(R.string.not_file_msg))
                    finish()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            UUtils.showMsg(UUtils.getString(R.string.copy_uri_error))
        }

    }

    private fun promptNameAndSave(inputStream: InputStream, attachmentFileName: String?) {
        TextInputDialogUtils.textInput(
            this,
            R.string.title_file_received,
            attachmentFileName,
            R.string.action_file_received_edit,
            { text ->
                val outFile = saveStreamWithName(inputStream, text)
                if (outFile == null) return@textInput

                val editorProgramFile = File(EDITOR_PROGRAM)
                if (!editorProgramFile.isFile) {
                    showErrorDialogAndQuit(
                        "The following file does not exist:\n\$HOME/bin/termux-file-editor\n\n" +
                            "Create this file as a script or a symlink - it will be called with the received file as only argument."
                    )
                    return@textInput
                }

                editorProgramFile.setExecutable(true)

                val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, UriUtils.getFileUri(EDITOR_PROGRAM))
                executeIntent.setClass(this, TermuxService::class.java)
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(outFile.absolutePath))
                startService(executeIntent)
                finish()
            },
            R.string.action_file_received_open_directory,
            { text ->
                if (saveStreamWithName(inputStream, text) == null) return@textInput

                val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE)
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR)
                executeIntent.setClass(this, TermuxService::class.java)
                startService(executeIntent)
                finish()
            },
            android.R.string.cancel,
            { finish() },
            { if (finishOnDismissNameDialog) finish() }
        )
    }

    private fun saveStreamWithName(inputStream: InputStream, attachmentFileName: String?): File? {
        val receiveDir = File(TERMUX_RECEIVEDIR)
        if (attachmentFileName.isNullOrEmpty()) {
            showErrorDialogAndQuit("File name cannot be null or empty")
            return null
        }

        if (!receiveDir.isDirectory && !receiveDir.mkdirs()) {
            showErrorDialogAndQuit("Cannot create directory: ${receiveDir.absolutePath}")
            return null
        }

        return try {
            val outFile = File(receiveDir, attachmentFileName)
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(4096)
                var readBytes: Int
                while (inputStream.read(buffer).also { readBytes = it } > 0) {
                    output.write(buffer, 0, readBytes)
                }
            }
            outFile
        } catch (e: IOException) {
            showErrorDialogAndQuit("Error saving file:\n\n$e")
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e)
            null
        }
    }

    private fun handleUrlAndFinish(url: String) {
        val urlOpenerProgramFile = File(URL_OPENER_PROGRAM)
        if (!urlOpenerProgramFile.isFile) {
            showErrorDialogAndQuit(
                "The following file does not exist:\n\$HOME/bin/termux-url-opener\n\n" +
                    "Create this file as a script or a symlink - it will be called with the shared URL as the first argument."
            )
            return
        }

        urlOpenerProgramFile.setExecutable(true)

        val executeIntent = Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, UriUtils.getFileUri(URL_OPENER_PROGRAM))
        executeIntent.setClass(this, TermuxService::class.java)
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf(url))
        startService(executeIntent)
        finish()
    }

    private fun showErrorDialogAndQuit(message: String) {
        finishOnDismissNameDialog = false
        MessageDialogUtils.showMessage(
            this,
            TermuxConstants.TERMUX_APP_NAME + "FileReceiver",
            message,
            null,
            { _, _ -> finish() },
            null,
            null,
            { finish() }
        )
    }

    private suspend fun showText(file: File, fileInputStream: FileInputStream) {
        UUtils.writerFileInput(file, fileInputStream) { l, isEnd ->
           UUtils.getHandler().post {
               if (!isEnd) {
                   LogUtils.d(TAG, "showText copy File: ${l}")
                   LogUtils.d(TAG, "showText File Size: ${mFile!!.length()}")
                   LogUtils.d(TAG, "showText %: ${l.toFloat() / mFile!!.length()}")
                   pro.progress = (l.toFloat() / mFile!!.length() * 100).toInt()
                   msg_pro.text = FileIOUtils.formatFileSize(l)
               } else {
                   UUtils.showMsg(UUtils.getString(R.string.copy_file_to_zt))
                   finish()
               }
           }
        }

    }




}
