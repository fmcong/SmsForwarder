package com.duanxinzhuanfa.xinxi.utils.mail

import jakarta.activation.DataHandler
import jakarta.activation.FileDataSource
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date

/**
 * 使用 OpenKeychain（OpenPGP API）加密/签名并发送邮件
 *
 * MimeMessage 构建与 PGP/MIME 封装逻辑与 PgpUtils 一致，
 * 区别在于加密/签名由 OpenKeychain 完成（本应用不接触密钥材料），
 * 收件人公钥由 OpenKeychain 按邮箱（User ID）自动匹配。
 */
@Suppress("PrivatePropertyName")
class OpenKeychainMailUtils(
    private val session: Session,
    // 邮件参数
    private val fromAlias: String, // 发件人邮箱别名(⚠️注意: 如果需要加密/签名，建议使用真实邮箱，避免收件人无法验证签名)
    private val nickname: String, // 发件人昵称
    private val subject: String, // 邮件主题
    private val body: String, // 邮件正文
    private val attachFiles: List<File> = emptyList(), // 附件
    // 收件人参数
    private val toAddress: List<String> = emptyList(), // 收件人邮箱
    private val ccAddress: List<String> = emptyList(), // 抄送者邮箱
    private val bccAddress: List<String> = emptyList(), // 密送者邮箱
    // OpenKeychain
    private val openKeychainHelper: OpenKeychainHelper,
    private val signKeyId: Long = 0L, // 签名密钥ID，0表示只加密不签名
) {
    private val TAG: String = OpenKeychainMailUtils::class.java.simpleName

    /** 发送加密（可选签名）邮件，公钥按收件人邮箱由OpenKeychain自动匹配 */
    fun sendEncryptedEmail(): Pair<Boolean, String> = try {
        if (toAddress.isEmpty()) throw IllegalArgumentException("收件人邮箱（toAddress）不能为空")
        val message = buildOriginalMessage()
        applyOpenKeychainToOriginalMessage(message)
        Transport.send(message)
        val signed = if (signKeyId != 0L) "signed and " else ""
        Pair(true, "OpenKeychain ${signed}encrypted email sent successfully")
    } catch (e: Exception) {
        e.printStackTrace()
        Pair(false, "Failed to send OpenKeychain encrypted email: ${e.message ?: e.toString()}")
    }

    /** 构建原始 MimeMessage（与 PgpUtils.buildOriginalMessage 一致） */
    private fun buildOriginalMessage(): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(fromAlias, nickname, "UTF-8"))
        if (toAddress.isNotEmpty()) {
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress.joinToString(",")))
        }
        if (ccAddress.isNotEmpty()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(ccAddress.joinToString(",")))
        }
        if (bccAddress.isNotEmpty()) {
            message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bccAddress.joinToString(",")))
        }
        message.subject = MimeUtility.encodeText(subject, "UTF-8", null)
        message.sentDate = Date()

        val multipart = MimeMultipart("mixed")

        val bodyPart = MimeBodyPart()
        bodyPart.setContent(body, "text/html;charset=UTF-8")
        multipart.addBodyPart(bodyPart)

        attachFiles.forEach { file ->
            val filePart = MimeBodyPart()
            val fileDs = FileDataSource(file)
            filePart.dataHandler = DataHandler(fileDs)
            filePart.fileName = MimeUtility.encodeText(file.name, "UTF-8", "B")
            multipart.addBodyPart(filePart)
        }

        message.setContent(multipart)
        message.saveChanges()
        return message
    }

    /**
     * 将原始 MimeMessage 交给 OpenKeychain 加密/签名，替换为 PGP/MIME(multipart/encrypted) 结构，
     * 保留所有原始头信息（与 PgpUtils.applyPgpToOriginalMessage 相同的封装规范）
     */
    private fun applyOpenKeychainToOriginalMessage(message: MimeMessage) {
        // 1. 读取原始message的完整字节流
        val originalBaos = ByteArrayOutputStream()
        message.writeTo(originalBaos)
        val originalInput = ByteArrayInputStream(originalBaos.toByteArray())

        // 2. 交给 OpenKeychain 加密（可选签名），按收件人邮箱匹配公钥
        val pgpBaos = ByteArrayOutputStream()
        openKeychainHelper.encrypt(toAddress.toTypedArray(), signKeyId, originalInput, pgpBaos)

        // 3. 构建PGP/MIME标准的multipart/encrypted结构
        val pgpMultipart = MimeMultipart("encrypted; protocol=\"application/pgp-encrypted\"")

        val versionPart = MimeBodyPart().apply {
            setText("Version: 1")
            setHeader("Content-Type", "application/pgp-encrypted")
            setHeader("Content-Description", "PGP/MIME version identification")
        }

        val contentPart = MimeBodyPart().apply {
            val dataSource = ByteArrayDataSource(pgpBaos.toByteArray(), "application/octet-stream")
            dataHandler = DataHandler(dataSource)
            setHeader("Content-Type", "application/octet-stream; name=\"encrypted.asc\"")
            setHeader("Content-Description", "OpenPGP encrypted/signed message")
            setHeader("Content-Disposition", "inline; filename=\"encrypted.asc\"")
        }

        pgpMultipart.addBodyPart(versionPart)
        pgpMultipart.addBodyPart(contentPart)

        // 4. 替换原始message的内容，保留所有原始头信息
        message.setContent(pgpMultipart)
        message.saveChanges()
    }
}
