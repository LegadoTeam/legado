package io.legado.app.help.storage

import cn.hutool.crypto.symmetric.AES
import io.legado.app.utils.MD5Utils

class BackupAES(password: String?) : AES(
    MD5Utils.md5Encode(password.orEmpty()).encodeToByteArray(0, 16)
)
