package com.duanxinzhuanfa.xinxi.entity.setting

import com.duanxinzhuanfa.xinxi.R
import java.io.Serializable

data class EmailSetting(
    var mailType: String = "",
    var fromEmail: String = "",
    var pwd: String = "",
    var nickname: String = "",
    var host: String = "",
    var port: String = "",
    var ssl: Boolean = false,
    var startTls: Boolean = false,
    var title: String = "",
    var recipients: MutableMap<String, Pair<String, String>> = mutableMapOf(),
    var toEmail: String = "",
    var keystore: String = "",
    var password: String = "",
    var encryptionProtocol: String = "Plain", //加密协议: S/MIME、OpenPGP、OpenKeychain、Plain（不传证书）
    var fromEmailAlias: String = "", //发件邮箱别名
    var openKeychainSignKeyId: Long = 0L, //OpenKeychain签名密钥ID（0=不签名）
    var openKeychainSignKeyDesc: String = "", //OpenKeychain签名密钥描述（仅用于界面回显）
) : Serializable {

    fun getEncryptionProtocolCheckId(): Int {
        return when (encryptionProtocol) {
            "S/MIME" -> R.id.rb_encryption_protocol_smime
            "OpenPGP" -> R.id.rb_encryption_protocol_openpgp
            "OpenKeychain" -> R.id.rb_encryption_protocol_openkeychain
            else -> R.id.rb_encryption_protocol_plain
        }
    }
}
