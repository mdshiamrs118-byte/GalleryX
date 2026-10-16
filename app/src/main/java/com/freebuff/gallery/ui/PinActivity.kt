package com.freebuff.gallery.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.freebuff.gallery.R
import com.freebuff.gallery.data.CollectionSpec
import com.freebuff.gallery.data.Prefs
import com.freebuff.gallery.databinding.ActivityPinBinding
import com.freebuff.gallery.util.toast

/**
 * 4-digit PIN pad used to unlock the hidden album, to create a PIN the first
 * time, and (from Settings) to verify or change the PIN.
 */
class PinActivity : AppCompatActivity() {

    private enum class Stage { VERIFY, NEW, CONFIRM }

    private lateinit var binding: ActivityPinBinding
    private lateinit var prefs: Prefs
    private lateinit var dots: List<View>

    private var mode = MODE_UNLOCK
    private var stage = Stage.VERIFY
    private val entered = StringBuilder()
    private var firstEntry = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPinBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        stage = if (prefs.hasPin()) Stage.VERIFY else Stage.NEW

        dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)

        val keys = listOf(
            binding.btnKey0, binding.btnKey1, binding.btnKey2, binding.btnKey3, binding.btnKey4,
            binding.btnKey5, binding.btnKey6, binding.btnKey7, binding.btnKey8, binding.btnKey9
        )
        keys.forEachIndexed { digit, button -> button.setOnClickListener { addDigit(digit) } }
        binding.btnKeyDelete.setOnClickListener {
            if (entered.isNotEmpty()) {
                entered.deleteCharAt(entered.length - 1)
                renderDots()
            }
        }

        renderDots()
        renderTitle()
    }

    private fun addDigit(digit: Int) {
        if (entered.length >= PIN_LENGTH) return
        entered.append(digit)
        renderDots()
        if (entered.length == PIN_LENGTH) {
            // Let the 4th dot render before moving on.
            binding.dots.postDelayed({ if (!isFinishing) onPinComplete() }, 120)
        }
    }

    private fun onPinComplete() {
        val pin = entered.toString()
        entered.clear()
        when (stage) {
            Stage.VERIFY -> {
                if (prefs.verifyPin(pin)) {
                    if (mode == MODE_CHANGE) {
                        stage = Stage.NEW
                        hideError()
                    } else {
                        finishUnlocked()
                        return
                    }
                } else {
                    showError(R.string.pin_wrong)
                }
            }
            Stage.NEW -> {
                firstEntry = pin
                stage = Stage.CONFIRM
                hideError()
            }
            Stage.CONFIRM -> {
                if (pin == firstEntry) {
                    prefs.setPin(pin)
                    toast(this, getString(R.string.pin_changed))
                    if (mode == MODE_UNLOCK) {
                        openHidden()
                    } else {
                        setResult(RESULT_OK)
                    }
                    finish()
                    return
                } else {
                    firstEntry = ""
                    stage = Stage.NEW
                    showError(R.string.pin_mismatch)
                }
            }
        }
        renderDots()
        renderTitle()
    }

    private fun finishUnlocked() {
        prefs.unlock()
        if (mode == MODE_UNLOCK) {
            openHidden()
        } else {
            setResult(RESULT_OK)
        }
        finish()
    }

    private fun openHidden() {
        CollectionActivity.start(this, getString(R.string.album_hidden), CollectionSpec("hidden"))
    }

    private fun renderDots() {
        dots.forEachIndexed { index, view ->
            view.setBackgroundResource(
                if (index < entered.length) R.drawable.bg_pin_dot_on else R.drawable.bg_pin_dot_off
            )
        }
    }

    private fun renderTitle() {
        binding.pinTitle.setText(
            when (stage) {
                Stage.VERIFY -> R.string.pin_enter
                Stage.NEW -> R.string.pin_setup
                Stage.CONFIRM -> R.string.pin_confirm
            }
        )
    }

    private fun showError(resId: Int) {
        binding.pinError.setText(resId)
        binding.pinError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.pinError.visibility = View.GONE
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"

        /** Unlock the hidden album (default). */
        const val MODE_UNLOCK = "unlock"

        /** Only verify the PIN; returns RESULT_OK on success. */
        const val MODE_VERIFY = "verify"

        /** Verify the current PIN (if any), then set a new one; returns RESULT_OK. */
        const val MODE_CHANGE = "change"

        private const val PIN_LENGTH = 4
    }
}
