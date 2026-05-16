package com.example.deprembitirmeprojesi.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.deprembitirmeprojesi.databinding.ActivityLoginBinding
import com.example.deprembitirmeprojesi.util.ThemeHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // OTOMATİK GİRİŞ KONTROLÜ
        val currentUser = auth.currentUser
        if (currentUser != null) {
            checkUserRole(currentUser.uid)
        }

        // Giriş yap butonu
        binding.loginButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                binding.loginButton.isEnabled = false // Butonu devre dışı bırak
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            user?.let { checkUserRole(it.uid) }
                        } else {
                            binding.loginButton.isEnabled = true
                            Toast.makeText(this, "Giriş başarısız: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } else {
                Toast.makeText(this, "E-posta ve şifre giriniz.", Toast.LENGTH_SHORT).show()
            }
        }

        // Kayıt ol yazısı
        binding.registerTextButton.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        // Şifremi unuttum yazısı
        binding.forgotPasswordText.setOnClickListener {
            val intent = Intent(this, ForgotPasswordActivity::class.java)
            startActivity(intent)
        }
    }

    private fun checkUserRole(uid: String) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val role = document.getString("role")
                    println("DEBUG_ROLE: Firestore'dan gelen rol: $role")
                    
                    when (role) {
                         "mudur" -> {
                            println("DEBUG_ROLE: Müdür/AFAD Paneline yönlendiriliyor...")
                            navigateToTeamLeaderActivity()
                        }
                        "personel" -> {
                            println("DEBUG_ROLE: Kurtarıcı Paneline yönlendiriliyor...")
                            navigateToEmergencyActivity()
                        }
                        else -> {
                            println("DEBUG_ROLE: Standart kullanıcı paneline yönlendiriliyor...")
                            navigateToMainActivity()
                        }
                    }
                } else {
                    // Belge yoksa varsayılan olarak ana ekrana
                    println("DEBUG_ROLE: Kullanıcı dokümanı Firestore'da bulunamadı!")
                    navigateToMainActivity()
                }
            }
            .addOnFailureListener { exception ->
                println("DEBUG_ROLE: Firestore hatası: ${exception.message}")
                binding.loginButton.isEnabled = true // Butonu tekrar etkinleştir
                navigateToMainActivity()
            }
    }

    private fun navigateToTeamLeaderActivity() {
        val intent = Intent(this, TeamLeaderActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun navigateToEmergencyActivity() {
        val intent = Intent(this, EmergencyActivity::class.java)
        startActivity(intent)
        finish()
    }
}
