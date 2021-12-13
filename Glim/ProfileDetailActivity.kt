package chat.glim.mobile.ui.activity

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Observer
import chat.glim.mobile.R
import chat.glim.mobile.data.model.entities.User
import chat.glim.mobile.databinding.ActivityProfileDetailBinding
import chat.glim.mobile.utils.ExifUtils
import chat.glim.mobile.utils.convertBitmapToFile
import chat.glim.mobile.utils.createFilename
import chat.glim.mobile.utils.resizeByWidth
import chat.glim.mobile.viewmodel.*
import com.bumptech.glide.Glide
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

private const val FINAL_TAKE_PHOTO = 1
private const val FINAL_CHOOSE_PHOTO = 2

class ProfileDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileDetailBinding
    private val viewModel: ProfileUserViewModel by viewModel()
    private lateinit var user: User

    private var imageUri: Uri? = null
    private lateinit var imageFile: File
    private lateinit var imageBitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.container.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        binding.layBack.setOnClickListener { onBackPressed() }
        binding.layName.setOnClickListener {
            val intent = Intent(this, ChangeProfileSubjectActivity::class.java)
            startActivity(intent)
        }
        binding.layInfo.setOnClickListener {
            val intent = Intent(this, ChangeProfileInfoActivity::class.java)
            startActivity(intent)
        }
        binding.btnChangePhoto.setOnClickListener {
            showChangePhotoDialog()
        }

        requestReadStoragePermission()
        showLoading()
        initObserver()
        viewModel.getUserLocal()
    }

    private fun initObserver() {
        viewModel.state.observe(this, Observer { state ->
            state?.let { it ->
                when(it){
                    is ProfileUserSuccess -> {
                        hideLoading()
                        user = it.user
                        loadAvatar()
                        binding.tvName.text = if (user.name.isEmpty()) user.phone else user.name
                        binding.tvInfo.text = user.info
                        binding.tvPhoneNumber.text = user.phone
                    }
                    is ProfileUserUpdateSuccess -> {
                        it.user?.let { user = it }
                        loadAvatar()
                    }
                    is ProfileUserFailed -> { showErrorDialog(it.error) }
                }
            }
        })
    }

    private fun loadAvatar() {
        Glide.with(this)
                .load(user.avatar)
                .placeholder(R.color.greyPhotoBorder)
                .into(binding.ivProfile)
    }

    private fun requestReadStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        when {
            ContextCompat.checkSelfPermission(this,
                    READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> {
                requestPermissions(arrayOf(READ_EXTERNAL_STORAGE), 101)
            }
            shouldShowRequestPermissionRationale(READ_EXTERNAL_STORAGE) -> {
                requestPermissions(arrayOf(READ_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun showChangePhotoDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_action_camera)
        dialog.setCancelable(true)

        val btnCamera = dialog.findViewById<LinearLayout>(R.id.btnCamera)
        btnCamera.setOnClickListener {
            checkCameraPermission()
            dialog.dismiss()
        }
        val btnGallery = dialog.findViewById<LinearLayout>(R.id.btnGaleri)
        btnGallery.setOnClickListener {
            checkGalleryPermission()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun checkCameraPermission() {
        val checkSelfPermission = ContextCompat.checkSelfPermission(this, CAMERA)
        if (checkSelfPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA), FINAL_TAKE_PHOTO)
        } else {
            openCamera()
        }
    }

    private fun checkGalleryPermission() {
        val checkSelfPermission = ContextCompat.checkSelfPermission(this, WRITE_EXTERNAL_STORAGE)
        if (checkSelfPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(WRITE_EXTERNAL_STORAGE), FINAL_CHOOSE_PHOTO)
        } else {
            openGallery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1 ->
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    openCamera()
                else
                    Toast.makeText(this, "You denied the permission", Toast.LENGTH_SHORT).show()
            2 ->
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    openGallery()
                else
                    Toast.makeText(this, "You denied the permission", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera() {
        imageFile = createFilename(externalCacheDir, "profile")
        imageUri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(this, "chat.glim.mobile.provider", imageFile)
        } else {
            Uri.fromFile(imageFile)
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
        intent.putExtra(MediaStore.EXTRA_SCREEN_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        startActivityForResult(intent, FINAL_TAKE_PHOTO)
    }

    private fun openGallery() {
        val gallery = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI)
        startActivityForResult(gallery, FINAL_CHOOSE_PHOTO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            FINAL_TAKE_PHOTO ->
                if (resultCode == Activity.RESULT_OK) {
                    imageBitmap = ExifUtils.getRotatedBitmap(this, imageUri).resizeByWidth(400)
                    convertBitmapToFile(imageFile, imageBitmap)
                    viewModel.profileUpdate(imageFile, user.name, user.info)
                }
            FINAL_CHOOSE_PHOTO ->
                if (resultCode == Activity.RESULT_OK) {
                    imageUri = data?.data
                    imageBitmap = ExifUtils.getRotatedBitmap(this, imageUri).resizeByWidth(400)
                    imageFile = createFilename(externalCacheDir,"profile")
                    convertBitmapToFile(imageFile, imageBitmap)
                    viewModel.profileUpdate(imageFile, user.name, user.info)
                }
        }
    }
}