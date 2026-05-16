package com.example.deprembitirmeprojesi.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.deprembitirmeprojesi.data.UserProfile
import com.example.deprembitirmeprojesi.databinding.ActivityProfileBinding
import com.example.deprembitirmeprojesi.util.ThemeHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // Toolbar ayarı
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        loadUserProfile()

        binding.btnSaveProfile.setOnClickListener {
            saveUserProfile()
        }
    }

    private fun loadUserProfile() {
        val user = auth.currentUser
        val userId = user?.uid ?: return
        
        binding.tvProfileEmail.text = user.email ?: ""

        firestore.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val profile = document.toObject(UserProfile::class.java)
                    profile?.let {
                        binding.etFullName.setText(it.fullName)
                        binding.tvProfileName.text = it.fullName.ifBlank { "Ad Soyad" }
                        binding.etTCKN.setText(it.tckn)
                        binding.etBirthDate.setText(it.birthDate)
                        binding.etBloodType.setText(it.bloodType)
                        binding.etChronicIllness.setText(it.chronicIllness)
                        binding.etRegularMedication.setText(it.regularMedication)
                        binding.etApartmentInfo.setText(it.apartmentInfo)
                        binding.etFloorInfo.setText(it.floorInfo)
                    }
                } else {
                    // Eğer doküman yoksa email bilgisini varsayılan olarak gösterelim
                    binding.tvProfileName.text = user.email ?: "Kullanıcı"
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Bilgiler yüklenemedi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun saveUserProfile() {
        val user = auth.currentUser
        val userId = user?.uid ?: return
        val userEmail = user.email ?: ""
        
        val name = binding.etFullName.text.toString()
        
        // Verileri UID ile ilişkilendirip email'i de içinde tutuyoruz
        val updates = hashMapOf(
            "fullName" to name,
            "email" to userEmail,
            "tckn" to binding.etTCKN.text.toString(),
            "birthDate" to binding.etBirthDate.text.toString(),
            "bloodType" to binding.etBloodType.text.toString(),
            "chronicIllness" to binding.etChronicIllness.text.toString(),
            "regularMedication" to binding.etRegularMedication.text.toString(),
            "apartmentInfo" to binding.etApartmentInfo.text.toString(),
            "floorInfo" to binding.etFloorInfo.text.toString()
        )

        firestore.collection("users").document(userId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                binding.tvProfileName.text = name.ifBlank { userEmail }
                Toast.makeText(this, "Profil başarıyla güncellendi.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Güncelleme hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
