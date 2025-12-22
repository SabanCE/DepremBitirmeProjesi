package com.example.deprembitirmeprojesi.ui

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.example.deprembitirmeprojesi.data.UserProfile
import com.example.deprembitirmeprojesi.databinding.ActivityProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        loadUserProfile()

        binding.btnSaveProfile.setOnClickListener {
            saveUserProfile()
        }
    }

    private fun loadUserProfile() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            firestore.collection("users").document(userId).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val userProfile = document.toObject(UserProfile::class.java)
                        if (userProfile != null) {
                            binding.etFullName.setText(userProfile.fullName)
                            binding.etTCKN.setText(userProfile.tckn)
                            binding.etBirthDate.setText(userProfile.birthDate)
                            binding.etBloodType.setText(userProfile.bloodType)
                            binding.etGender.setText(userProfile.gender)
                            binding.etDisabilityStatus.setText(userProfile.disabilityStatus)
                            binding.etChronicIllness.setText(userProfile.chronicIllness)
                            binding.etAllergies.setText(userProfile.allergies)
                            binding.etRegularMedication.setText(userProfile.regularMedication)
                            binding.etPregnancyStatus.setText(userProfile.pregnancyStatus)
                            binding.etApartmentInfo.setText(userProfile.apartmentInfo)
                            binding.etFloorInfo.setText(userProfile.floorInfo)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Bilgiler yüklenirken hata oluştu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun saveUserProfile() {
        val userId = auth.currentUser?.uid
        if (userId != null) {
            val userProfileUpdates = mapOf(
                "fullName" to binding.etFullName.text.toString(),
                "tckn" to binding.etTCKN.text.toString(),
                "birthDate" to binding.etBirthDate.text.toString(),
                "bloodType" to binding.etBloodType.text.toString(),
                "gender" to binding.etGender.text.toString(),
                "disabilityStatus" to binding.etDisabilityStatus.text.toString(),
                "chronicIllness" to binding.etChronicIllness.text.toString(),
                "allergies" to binding.etAllergies.text.toString(),
                "regularMedication" to binding.etRegularMedication.text.toString(),
                "pregnancyStatus" to binding.etPregnancyStatus.text.toString(),
                "apartmentInfo" to binding.etApartmentInfo.text.toString(),
                "floorInfo" to binding.etFloorInfo.text.toString()
            )

            firestore.collection("users").document(userId).update(userProfileUpdates)
                .addOnSuccessListener {
                    Toast.makeText(this, "Profil bilgileri başarıyla güncellendi.", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Güncellerken hata oluştu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}