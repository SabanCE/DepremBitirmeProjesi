package com.example.deprembitirmeprojesi.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.deprembitirmeprojesi.data.UserProfile
import com.example.deprembitirmeprojesi.databinding.ActivityRegisterBinding
import com.example.deprembitirmeprojesi.util.Constants
import com.example.deprembitirmeprojesi.util.ThemeHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = Firebase.auth

        // Geri butonu işlevselliği
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Giriş yap sayfasına yönlendirme
        binding.loginTextButton.setOnClickListener {
            finish() // Zaten LoginActivity'den gelmiş olabiliriz, değilsek de finish() mantıklı
        }

        binding.registerButton.setOnClickListener {
            binding.registerButton.isEnabled = false

            val nameSurname = binding.nameSurnameInput.text.toString().trim()
            val email = binding.emailInput.text.toString().trim()
            val password = binding.passwordInput.text.toString().trim()

            if (nameSurname.isNotEmpty() && email.isNotEmpty() && password.isNotEmpty()) {
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val user = auth.currentUser
                            if (user != null) {
                                val userProfile = UserProfile(
                                    fullName = nameSurname,
                                    email = email
                                )
                                val db = Firebase.firestore
                                db.collection(Constants.FIRESTORE_COLLECTION_USERS).document(user.uid)
                                    .set(userProfile)
                                    .addOnSuccessListener {
                                        // Kayıt başarılı olduğunda rolü kaydet (Default "user")
                                        ThemeHelper.setLastUserRole(this, userProfile.role)

                                        Toast.makeText(this, "Kayıt Başarılı! Yönlendiriliyorsunuz...", Toast.LENGTH_LONG).show()

                                        Handler(Looper.getMainLooper()).postDelayed({
                                            val intent = Intent(this, MainActivity::class.java)
                                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                            startActivity(intent)
                                            finish()
                                        }, 2000)
                                    }
                                    .addOnFailureListener { e ->
                                        Toast.makeText(this, "Firestore hata: ${e.message}", Toast.LENGTH_LONG).show()
                                        binding.registerButton.isEnabled = true
                                    }
                            } else {
                                Toast.makeText(this, "Kullanıcı oluşturulamadı.", Toast.LENGTH_SHORT).show()
                                binding.registerButton.isEnabled = true
                            }
                        } else {
                            Toast.makeText(this, "Kayıt Başarısız: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                            binding.registerButton.isEnabled = true
                        }
                    }
            } else {
                Toast.makeText(this, "Lütfen tüm alanları doldurun", Toast.LENGTH_SHORT).show()
                binding.registerButton.isEnabled = true
            }
        }
    }
}