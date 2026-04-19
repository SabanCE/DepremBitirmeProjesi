package com.example.deprembitirmeprojesi.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.deprembitirmeprojesi.databinding.ActivityLoginBinding
import com.example.deprembitirmeprojesi.util.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth
        firestore = FirebaseFirestore.getInstance()

        // Eğer Kullanıcı girmiş ise sisteme
        val currentUser = auth.currentUser
        if (currentUser != null) {
            checkUserRoleAndNavigate(currentUser.uid)
        }

        binding.loginButton.setOnClickListener {
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                binding.loginButton.isEnabled = false // Butona tekrar basılmasın
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            if (user != null) {
                                checkUserRoleAndNavigate(user.uid)
                            }
                        } else {
                            val exception = task.exception
                            when (exception) {
                                is FirebaseAuthInvalidUserException -> {
                                    Toast.makeText(this, "Kullanıcı bulunamadı", Toast.LENGTH_SHORT).show()
                                }
                                is FirebaseAuthInvalidCredentialsException -> {
                                    Toast.makeText(this, "Geçersiz e-posta veya şifre", Toast.LENGTH_SHORT).show()
                                }
                                else -> {
                                    Toast.makeText(this, "Giriş başarısız: ${exception?.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            binding.loginButton.isEnabled = true // Butonu tekrar etkinleştir
                        }
                    }
            } else {
                Toast.makeText(this, "Lütfen tüm alanları doldurun", Toast.LENGTH_SHORT).show()
            }
        }

        binding.registerTextButton.setOnClickListener {
             val intent = Intent(this, RegisterActivity::class.java)
             startActivity(intent)
        }

        binding.forgotPasswordText.setOnClickListener {
            Toast.makeText(this, "Şifremi unuttum özelliği henüz eklenmedi.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkUserRoleAndNavigate(userId: String) {
        firestore.collection(Constants.FIRESTORE_COLLECTION_USERS).document(userId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val role = document.getString("role")

                    // Debug için log ekleyelim (Logcat'ten kontrol edebilirsin)
                    println("DEBUG_ROLE: Firestore'dan gelen rol: '$role', Beklenen: '${Constants.ROLE_PERSONNEL}'")

                    // equals(..., ignoreCase = true) kullanarak büyük/küçük harf hatasını önleyelim
                    if (role?.equals(Constants.ROLE_PERSONNEL, ignoreCase = true) == true) {
                        navigateToEmergencyActivity()
                    } else {
                        navigateToMainActivity()
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

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToEmergencyActivity() {
        val intent = Intent(this, EmergencyActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
