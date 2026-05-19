package top.bilibili.webui.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiPasswordPolicyTest {
    @Test
    fun `password should require at least eight characters`() {
        val result = WebUiPasswordPolicy.validate("Aa1!")

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("8") })
    }

    @Test
    fun `password should require letters digits and special characters`() {
        val lettersOnly = WebUiPasswordPolicy.validate("Abcdefgh")
        val digitsOnly = WebUiPasswordPolicy.validate("12345678")
        val missingSpecial = WebUiPasswordPolicy.validate("Abcd1234")

        assertFalse(lettersOnly.isValid)
        assertFalse(digitsOnly.isValid)
        assertFalse(missingSpecial.isValid)
    }

    @Test
    fun `password should reject spaces and accept compliant value`() {
        val invalid = WebUiPasswordPolicy.validate("Abc 123!")
        val valid = WebUiPasswordPolicy.validate("Abc123!@")

        assertFalse(invalid.isValid)
        assertTrue(invalid.errors.any { it.contains("空格") })
        assertTrue(valid.isValid)
    }
}
