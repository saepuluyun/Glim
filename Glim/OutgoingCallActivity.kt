package chat.glim.mobile.ui.activity

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import chat.glim.mobile.R
import chat.glim.mobile.data.model.CallDirection
import chat.glim.mobile.data.model.CallInvitationType
import chat.glim.mobile.data.model.CallParticipantModel
import chat.glim.mobile.data.model.CallState
import chat.glim.mobile.databinding.ActivityOutgoingCallBinding
import chat.glim.mobile.services.GlimFirebaseMessagingService
import chat.glim.mobile.utils.Constant
import chat.glim.mobile.utils.Global
import chat.glim.mobile.utils.formatSecondsTime
import chat.glim.mobile.utils.removeViewFromParent
import chat.glim.mobile.viewmodel.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import io.agora.rtc.ss.ScreenSharingClient
import io.agora.rtc.video.VideoEncoderConfiguration
import jp.wasabeef.glide.transformations.BlurTransformation
import org.koin.androidx.viewmodel.ext.android.viewModel


class OutgoingCallActivity : BaseActivity(){

    private lateinit var binding: ActivityOutgoingCallBinding
    private val viewModel : OutgoingCallViewModel by viewModel()

    private var mMediaPlayer: MediaPlayer? = null
    private var currentDuration = 0
    private var currentCallState:CallState = CallState.NONE
    private var localStreamView:SurfaceView? = null
    private var localScreenShareStreamView:SurfaceView? = null
    private var switchCallDialog:Dialog? = null

    private var isSharing:Boolean = false
    private var viewOnTap:Boolean = false
    private var handler = Handler()
    private lateinit var mSSClient: ScreenSharingClient

    private val mListener: ScreenSharingClient.IStateListener = object : ScreenSharingClient.IStateListener {
        override fun onError(error: Int) {
            Log.e("Screenshare", "Screen share service error happened: $error")
        }

        override fun onTokenWillExpire() {
            Log.d("Screenshare", "Screen share service token will expire")
        }
    }

    private val callTimer = object: CountDownTimer(Constant.CALL_TIMEOUT_SEC * 1000, 1000) {
        override fun onTick(millisUntilFinished: Long) {}

        override fun onFinish() {
            //call not answered,finish early
            val myState = viewModel.getMyState()
            if (myState == CallState.RINGING || myState == CallState.ENDED){
                stopRingingAudio()
                viewModel.endCall(CallState.ENDED, true)
            } else{
                viewModel.updateParticipant(true)
            }
        }
    }

    val broadCastReceiver = object : BroadcastReceiver() {
        override fun onReceive(contxt: Context?, intent: Intent?) {
            if(intent?.action == GlimFirebaseMessagingService.SWITCH_CALL_BROADCAST){
                when(intent.getStringExtra("ACTION")){
                    GlimFirebaseMessagingService.SWITCH_CALL_ACCEPT_REQUEST_KEY -> {
                        switchCallDialog?.dismiss()
                        switchCallDialog = null
                        viewModel.switchCall()
                    }

                    GlimFirebaseMessagingService.SWITCH_CALL_REJECT_REQUEST_KEY -> {
                        switchCallDialog?.dismiss()
                        switchCallDialog = null
                        switchCallDialog = showMessageDialog(getString(R.string.call_change_denied))
                    }

                    GlimFirebaseMessagingService.SWITCH_CALL_CANCEL_REQUEST_KEY -> {
                        switchCallDialog?.dismiss()
                        switchCallDialog = null
                        switchCallDialog = showMessageDialog(getString(R.string.call_change_cancelled))
                    }

                    GlimFirebaseMessagingService.SWITCH_CALL_REQUEST_KEY -> {
                        try {
                            val target = viewModel.getActiveParticipants(viewModel.currentUser?.id ?: 0).first()
                            target.let {
                                switchCallDialog?.dismiss()
                                switchCallDialog = null
                                switchCallDialog = showConfirmationDialog(getString(R.string.call_change_ask, target.name),getString(R.string.call_change_ask_deny_button),getString(R.string.call_change_ask_accept_button),{
                                    switchCallDialog = null
                                    viewModel.rejectSwitchCall()
                                },{
                                    switchCallDialog = null
                                    viewModel.acceptSwitchCall()
                                })
                            }
                        }catch(e:NoSuchElementException){

                        }

                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
        super.onCreate(savedInstanceState)

        binding = ActivityOutgoingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if(havePermissions()) {
            initActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        mMediaPlayer?.start()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(broadCastReceiver, IntentFilter(GlimFirebaseMessagingService.SWITCH_CALL_BROADCAST))
    }

    override fun onPause() {
        super.onPause()

        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(broadCastReceiver)
    }

    override fun onDestroy() {
        viewModel.endCall(CallState.ENDED, false)
        viewModel.onClear()
        stopRingingAudio()
        callTimer.cancel()
        cleanMediaPlayer()
        viewModel.clearRTC()
        Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
        if(isSharing) {
            mSSClient.stop(this)
        }
        super.onDestroy()
    }

    private fun initActivity(){
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)

        initView()
        initObserver()

        val callParticipants = intent.getParcelableArrayListExtra<CallParticipantModel>(
            CALL_PARAM_PARTICIPANTS
        )
        val callInvitationType = CallInvitationType.fromInt(
            intent.getIntExtra(
                CALL_PARAM_TYPE,
                CallInvitationType.AUDIO.id
            )
        )

        viewModel.initialize(callParticipants, callInvitationType, CallDirection.OUTGOING)

        initScreenShare()
    }

    private fun initScreenShare(){
        mSSClient = ScreenSharingClient.getInstance()
        mSSClient.setListener(mListener)
    }

    private fun initView() {
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        updateControlUI()

        binding.videoCallButton.setOnClickListener {
            viewModel.requestSwitchCall()
        }

        binding.declineButton.setOnClickListener {
            when (currentCallState) {
                CallState.ONGOING -> {
                    stopRingingAudio()
                    viewModel.endCall(CallState.ENDED, false)
                }
                CallState.RINGING -> {
                    stopRingingAudio()
                    viewModel.endCall(CallState.ENDED, true)
                }
                CallState.NONE -> {
                    stopRingingAudio()
                    viewModel.endCall(CallState.NONE, false)
                }
                else -> {
                    viewModel.leaveChannel()
                }
            }
        }

        binding.addContactButton.setOnClickListener {
            val intent = Intent(this, AddCallActivity::class.java)
            intent.putIntegerArrayListExtra(
                AddCallActivity.PARAM_EXCLUDED_CONTACT_IDS,
                ArrayList(viewModel.currentParticipants.filter {
                    val state = viewModel.currentCallLiveData?.getStateFor(it.id)
                    state == CallState.ONGOING || state == CallState.RINGING || state == CallState.READY
                }.map { it.id })
            )
            startActivityForResult(intent, REQUEST_ADD_CALL_PARTICIPANT)
        }

        binding.speakerButton.setOnClickListener{
            viewModel.toggleSpeakerActive()
        }

        binding.muteButton.setOnClickListener{
            viewModel.toggleMicMuted()
        }

        binding.switchCameraButton.setOnClickListener{
            viewModel.toggleFrontCamera()
        }

        binding.muteCamVideoButton.setOnClickListener{
            viewModel.toggleCameraMuted()
        }

        binding.muteMicVideoButton.setOnClickListener{
            viewModel.toggleMicMuted()
        }

        binding.shareScreenButton.setOnClickListener {
            if(!isSharing) {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
                    switchToSchareScreen()
                    isSharing = true

                    binding.shareScreenButton.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            this,
                            R.color.primaryRed
                        )
                    )
                    binding.shareScreenButton.iconTint = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            this,
                            (R.color.white)
                        )
                    )
                }else{
                    AlertDialog.Builder(this).setTitle("").setMessage(getString(R.string.screen_share_not_supported))
                        .setPositiveButton(getString(R.string.ok)
                        ) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
                        .show()
                }
            }else{
                mSSClient.stop(this)
                isSharing = false

                binding.shareScreenButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.white
                    )
                )
                binding.shareScreenButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        (R.color.black)
                    )
                )
            }
        }
    }

    private fun switchToSchareScreen(){
        if (!isSharing) {
            mSSClient.start(
                this,
                resources.getString(R.string.agora_app_id),
                viewModel.agoraToken,
                viewModel.agoraChannel,
                viewModel.myId + 1000000, //+ 1000000 id indicates share screen stream
                VideoEncoderConfiguration(
                    getScreenDimensions(),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                )
            )
            isSharing = true
        } else {
            mSSClient.stop(this)
            isSharing = false
        }
    }

    private fun initObserver() {
        viewModel.loading.observe(this, {
            it?.let {
                if (it) showLoading() else hideLoading()
            }
        })

        viewModel.durationTicker.observe(this, {
            it?.let {
                currentDuration = it
                updateDurationUI()
            }
        })

        viewModel.isCameraMuted.observe(this, { isSpeakerActive ->
            if (isSpeakerActive) {
                binding.muteCamVideoButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.primaryRed
                    )
                )
                binding.muteCamVideoButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        (R.color.white)
                    )
                )
                binding.localVideoViewContainer.visibility = View.GONE
            } else {
                binding.muteCamVideoButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        (R.color.white)
                    )
                )
                binding.muteCamVideoButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        (R.color.black)
                    )
                )
                binding.localVideoViewContainer.visibility = View.VISIBLE
            }
        })

        viewModel.isSpeakerActive.observe(this, { isSpeakerActive ->
            if (isSpeakerActive) {
                binding.speakerButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.primaryBlue
                    )
                )
                binding.speakerButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        (R.color.white)
                    )
                )
            } else {
                binding.speakerButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        (R.color.white)
                    )
                )
                binding.speakerButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        (R.color.black)
                    )
                )
            }
        })

        viewModel.isMicMuted.observe(this, { isSelfMuted ->
            if (isSelfMuted) {
                binding.muteButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.primaryRed
                    )
                )
                binding.muteButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.white
                    )
                )
                binding.muteMicVideoButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.primaryRed
                    )
                )
                binding.muteMicVideoButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.white
                    )
                )
            } else {
                binding.muteButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.white
                    )
                )
                binding.muteButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.black
                    )
                )
                binding.muteMicVideoButton.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.white
                    )
                )
                binding.muteMicVideoButton.iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this,
                        R.color.black
                    )
                )
            }
        })

        viewModel.isRemoteCameraMuted.observe(this, { isMuted ->
            if (isMuted) {
                binding.remoteVideoViewContainer.visibility = View.GONE
                binding.backgroundImageView.visibility = View.VISIBLE
            } else {
                binding.remoteVideoViewContainer.visibility = View.VISIBLE
                binding.backgroundImageView.visibility = View.GONE
            }
        })

        viewModel.state.observe(this, {
            when (it) {
                is CallRemoteVideoStreamReady -> {
                    Log.d("refreshCallStateUI ", "CallRemoteVideoStreamReady")
                    refreshCallStateUI()
                }
                is CallLocalVideoStreamReady -> {
                    localStreamView = it.view
                }
                is CallLocalScreenShareVideoStreamReady -> {
                    localScreenShareStreamView = it.view
                    refreshCallStateUI()
                }
                is CallLocalScreenShareVideoStreamEnded -> {
                    localScreenShareStreamView = null
                    refreshCallStateUI()
                }
                is CallTargetReady -> {
                    initRinggingUI(it.targets)
                    Log.d("CALL-FLOW", "CallTargetReady")
                    viewModel.inviteParticipants()
                }
                is CallTokenReady -> {
                    startRingingAudio()
                    callTimer.start()
                }
                is CallUserReady -> {
                    viewModel.initCall()
                }
                is CallUpdated -> {
                    currentCallState = viewModel.getMyState()
                    refreshCallStateUI()
                    if (currentCallState == CallState.ENDED || !it.data.haveInProgressCall()) {
                        viewModel.leaveChannel()
                    } else if (it.data.haveOngoingCall()) {
                        stopRingingAudio()
                        var participantIds = viewModel.currentCallLiveData?.getTargetIdByState(viewModel.currentUser!!.id.toString(), CallState.RINGING)

                        if (participantIds!!.isNotEmpty()){
                            callTimer.cancel()
                            callTimer.start()
                        }

                        if (viewModel.isVideoCall()) {
                            if (viewOnTap) {
                                viewOnTap = false
                                onHiddenControl()
                                handler.removeCallbacksAndMessages(null)
                            } else {
                                viewOnTap = true
                                onShowControl()
                                handler.postDelayed({
                                    viewOnTap = false
                                    onHiddenControl()
                                    handler.removeCallbacksAndMessages(null)
                                }, 3000)
                            }
                        }
                    }
                }
                is CallErrorTerminated -> {
                    Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
                    stopRingingAudio()
                    val message =
                        if (it.errMsg.isEmpty()) getString(R.string.calling_personal_failed) else it.errMsg
                    showErrorDialog(message) {
                        viewModel.leaveChannel()
                    }
                }

                is CallAddParticipantFailed -> {
                    val message =
                        if (it.errMsg.isEmpty()) getString(R.string.call_add_participant_failed) else it.errMsg
                    showErrorDialog(message) {
                    }

                }
                is CallEnded -> {
                    Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
                    finish()
                }
                is CallErrorNetworkTerminated -> {
                    Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
                    stopRingingAudio()
                    val message =
                        if (it.errMsg.isEmpty()) getString(R.string.call_network_interrupted) else it.errMsg
                    showErrorDialog(message) {
                        viewModel.leaveChannel()
                    }
                }
                is CallSwitchSuccess -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                    switchCallDialog = showMessageDialog(getString(R.string.call_change_wait_approval), getString(R.string.call_change_cancel_button)){
                        viewModel.cancelSwitchCall()
                    }
                }

                is CallSwitchFailed -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                    val message =
                        if (it.errMsg.isEmpty()) getString(R.string.call_change_failed) else it.errMsg
                    switchCallDialog = showErrorDialog(message) {

                    }

                }

                is CallSwitchAcceptSuccess -> {
                    viewModel.switchCall()
                }

                is CallSwitchRejectSuccess -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                }

                is CallSwitchCancelSuccess -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                }

                is CallSwitchCancelFailed -> {
                    switchCallDialog?.dismiss()
                    switchCallDialog = null
                    val message =
                        if (it.errMsg.isEmpty()) getString(R.string.call_change_failed) else it.errMsg
                    switchCallDialog = showErrorDialog(message) {
                        switchCallDialog = null
                        switchCallDialog = showMessageDialog(getString(R.string.call_change_wait_approval), getString(R.string.call_change_cancel_button)){
                            viewModel.cancelSwitchCall()
                        }
                    }
                }

                else -> {
                }
            }
        })
    }

    private fun initRinggingUI(targets: List<CallParticipantModel>){
        if(targets.size == 2){ // 1 on 1
            val target = targets.find { it.id != viewModel.myId }
            if(target != null) {
                binding.participantGrid.visibility = View.GONE
                binding.photoImageView.visibility = View.VISIBLE
                binding.backgroundImageView.visibility = View.VISIBLE

                Glide.with(applicationContext)
                    .load(target.avatar)
                    .placeholder(R.color.greyPhotoBorder)
                    .error(R.color.greyPhotoBorder)
                    .apply(bitmapTransform(BlurTransformation(44)))
                    .into(binding.backgroundImageView)

                Glide.with(applicationContext)
                    .load(target.avatar)
                    .placeholder(R.color.greyPhotoBorder)
                    .error(R.color.greyPhotoBorder)
                    .into(binding.photoImageView)


                binding.nameTextView.text = target.name
                binding.timerTextView.text = getString(R.string.calling_personal)
            }
        }else{
            //group call
            binding.participantGrid.visibility = View.VISIBLE
            binding.photoImageView.visibility = View.GONE
            binding.backgroundImageView.visibility = View.GONE
            binding.localVideoViewContainer.visibility = View.GONE
            binding.remoteVideoViewContainer.visibility = View.GONE

            val participantCount = viewModel.currentParticipants.size
            var counter = 1
            for (view in binding.participantGrid.children) {
                val lp = GridLayout.LayoutParams(view.layoutParams)

                //Set Show Hide
                if (counter <= participantCount) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)

                    Glide.with(applicationContext)
                        .load(viewModel.currentParticipants[counter - 1].avatar)
                        .placeholder(R.color.greyPhotoBorder)
                        .error(R.color.greyPhotoBorder)
                        .into((view as RelativeLayout).children.first() as ImageView)
                    val statusTextView = view.children.elementAt(1) as? TextView
                    if(statusTextView != null){
                        statusTextView.text =  getString(R.string.calling_personal)
                    }
                } else {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)

                }

                //set Last Row if participant have odds amount
                if (counter == participantCount && counter % 2 == 1) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 2, 1f)
                }
                view.layoutParams = lp
                counter += 1
            }
        }
    }

    private fun havePermissions(): Boolean {
        //check for camera & mic permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                PERMISSION_REQ_ID_CALL
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQ_ID_CALL -> {
                //simultaneously request two permisisons
                if (grantResults.size == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    initActivity()
                } else {
                    showErrorDialog(getString(R.string.calling_personal_failed_permission)) {
                        Global.userCallState = Constant.USER_CALL_STATE_AVAILABLE
                        finish()
                    }
                }
            }
        }
    }

    private fun refreshCallStateUI(){
        Log.d("refreshCallStateUI ", "Original part " + viewModel.currentParticipants.size)
        //filter inactive users & self
        val activeParticipant = viewModel.currentParticipants.filter {
             val participantState = viewModel.currentCallLiveData!!.getStateFor(it.id)

            Log.d(
                "refreshCallStateUI ",
                "Filtering " + it.id + " " + it.name + " " + participantState.toString()
            )
            (participantState == CallState.ONGOING || participantState == CallState.RINGING || participantState == CallState.READY)
        }
        Log.d("refreshCallStateUI ", "Filtered part " + activeParticipant.size)
        if(activeParticipant.size <= 2){
            binding.participantGrid.visibility = View.GONE

            val target = viewModel.currentParticipants.find {
                val participantState = viewModel.currentCallLiveData!!.getStateFor(it.id)
                Log.d(
                    "refreshCallStateUI ",
                    "Filtering one on one " + it.id + " " + participantState + " " + viewModel.myId
                )
                it.id != viewModel.myId && (participantState == CallState.ONGOING || participantState == CallState.RINGING || participantState == CallState.READY)
            }
            if(target != null) {
                Log.d("refreshCallStateUI ", "target picked " + target.id + " " + target.name)
                binding.participantGrid.visibility = View.GONE
                binding.nameTextView.text = target.name

                val targetState = viewModel.currentCallLiveData?.getStateFor(target.id)
                if(targetState == CallState.RINGING){
                    //if ringging, show name, profile picture & blurred background
                    binding.timerTextView.text = getString(R.string.calling_personal)
                    load1On1AudioUI(target)
                }else if (targetState == CallState.REJECTED) {
                    binding.timerTextView.text = getString(R.string.call_rejected)
                }else if (targetState == CallState.UNANSWERED) {
                    binding.timerTextView.text = getString(R.string.call_unanswered)
                }else if(targetState == CallState.ONGOING){
                    if(viewModel.isVideoCall()){
                        load1On1VideoUI(target)
                    }else{
                        load1On1AudioUI(target)
                    }
                }else{
                    binding.timerTextView.text = ""
                }
            }
        }else{
            //group call
            binding.participantGrid.visibility = View.VISIBLE
            binding.photoImageView.visibility = View.GONE
            binding.backgroundImageView.visibility = View.GONE
            binding.localVideoViewContainer.visibility = View.GONE
            binding.remoteVideoViewContainer.visibility = View.GONE

            //group call
            val participantCount = activeParticipant.size
            var counter = 1
            for (view in binding.participantGrid.children) {
                val lp = GridLayout.LayoutParams(view.layoutParams)

                //Set Show Hide
                if (counter <= participantCount) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    val participant = activeParticipant[counter - 1]
                    val statusTextView = (view as RelativeLayout).children.elementAt(1) as? TextView
                    if(statusTextView != null && viewModel.currentCallLiveData != null){
                        val participantState = viewModel.currentCallLiveData!!.getStateFor(
                            participant.id
                        )
                        if(participant.id == viewModel.myId){
                            if(viewModel.isVideoCall()){
                                if(participant.id == viewModel.myId && localStreamView != null){
                                    if(isSharing && localScreenShareStreamView != null){
                                        removeViewFromParent(localScreenShareStreamView!!)
                                        view.addView(localScreenShareStreamView)
                                    }else {
                                        removeViewFromParent(localStreamView!!)
                                        view.addView(localStreamView)
                                    }
                                    statusTextView.text = ""
                                }else{
                                    statusTextView.text = getString(R.string.call_name_self)
                                }
                            }else{
                                Glide.with(applicationContext)
                                    .load(viewModel.currentParticipants[counter - 1].avatar)
                                    .placeholder(R.color.greyPhotoBorder)
                                    .error(R.color.greyPhotoBorder)
                                    .into(view.children.first() as ImageView)
                            }
                        }else {
                            Glide.with(applicationContext)
                                .load(viewModel.currentParticipants[counter - 1].avatar)
                                .placeholder(R.color.greyPhotoBorder)
                                .error(R.color.greyPhotoBorder)
                                .into(view.children.first() as ImageView)
                            if (participantState == CallState.RINGING) {
                                statusTextView.text = getString(R.string.calling_personal)
                            } else if (participantState == CallState.REJECTED) {
                                statusTextView.text = getString(R.string.call_rejected)
                            } else if (participantState == CallState.UNANSWERED) {
                                statusTextView.text = getString(R.string.call_unanswered)
                            } else if (participantState == CallState.READY || participantState == CallState.ONGOING) {
                                if(viewModel.isVideoCall() && participant.videoView != null){
                                    if(participant.isScreenSharing && participant.screenShareVideoView != null){
                                        removeViewFromParent(participant.screenShareVideoView!!)
                                        view.addView(participant.screenShareVideoView)
                                    }else {
                                        removeViewFromParent(participant.videoView!!)
                                        view.addView(participant.videoView)
                                    }

                                    statusTextView.text = ""
                                }else{
                                    statusTextView.text = participant.name
                                }
                            } else {
                                statusTextView.text = ""
                            }
                        }
                    }
                } else {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)
                    lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 0, 1f)

                }

                //set Last Row if participant have odds amount
                if (counter == participantCount && counter % 2 == 1) {
                    lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 2, 1f)
                }
                view.layoutParams = lp
                counter += 1
            }
        }
        updateControlUI()
    }

    private fun updateDurationUI(){
        if(viewModel.currentCallLiveData?.haveOngoingCall() == true){
            binding.nameTextView.visibility = View.VISIBLE
            binding.timerTextView.visibility = View.VISIBLE
            binding.timerTextView.text = currentDuration.formatSecondsTime()
            updateControlUI()
        }
    }

    private fun startRingingAudio(){
        Global.userCallState = Constant.USER_CALL_STATE_UNAVAILABLE_REASON_ON_ANOTHER_CALL
        cleanMediaPlayer()

        mMediaPlayer = MediaPlayer.create(this, R.raw.phone_ring)

        val audioAttribute = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        mMediaPlayer?.setAudioAttributes(audioAttribute)
        mMediaPlayer?.isLooping = true
        mMediaPlayer?.start()
    }

    private fun stopRingingAudio(){
        mMediaPlayer?.stop()
    }

    private fun cleanMediaPlayer(){
        mMediaPlayer?.stop()
        mMediaPlayer?.reset()
        mMediaPlayer?.release()
        mMediaPlayer = null
    }

    private fun load1On1AudioUI(target: CallParticipantModel){
        binding.photoImageView.visibility = View.VISIBLE
        binding.backgroundImageView.visibility = View.VISIBLE

        binding.localVideoViewContainer.visibility = View.GONE
        binding.remoteVideoViewContainer.visibility = View.GONE

        Glide.with(applicationContext)
            .load(target.avatar)
            .placeholder(R.color.greyPhotoBorder)
            .error(R.color.greyPhotoBorder)
            .apply(bitmapTransform(BlurTransformation(44)))
            .into(binding.backgroundImageView)

        Glide.with(applicationContext)
            .load(target.avatar)
            .placeholder(R.color.greyPhotoBorder)
            .error(R.color.greyPhotoBorder)
            .into(binding.photoImageView)
    }

    private fun load1On1VideoUI(target: CallParticipantModel){
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding.shareScreenButton.visibility = View.VISIBLE

        binding.nameTextView.textSize = 16F
        binding.timerTextView.textSize = 10F

        binding.photoImageView.visibility = View.GONE
        binding.backgroundImageView.visibility = View.GONE

        binding.localVideoViewContainer.visibility = View.VISIBLE
        binding.remoteVideoViewContainer.visibility = View.VISIBLE

        if(target.videoView != null) {
            if(target.isScreenSharing && target.screenShareVideoView != null) {
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading remote screenshare for ${target.id}")
                removeViewFromParent(target.screenShareVideoView!!)
                binding.remoteVideoViewContainer.addView(target.screenShareVideoView)
            }else{
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading remote camera for ${target.id}")
                removeViewFromParent(target.videoView!!)
                binding.remoteVideoViewContainer.addView(target.videoView)
            }
        }

        if(localStreamView != null) {
            if(isSharing && localScreenShareStreamView != null) {
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading local screenshare")
                removeViewFromParent(localScreenShareStreamView!!)
                binding.localVideoViewContainer.addView(localScreenShareStreamView)
            }else{
                Log.d("AGORA-TRACE ", "load1On1VideoUI loading local camera")
                removeViewFromParent(localStreamView!!)
                binding.localVideoViewContainer.addView(localStreamView)
            }
        }

        Log.d(
            "CALL-FLOW",
            "CallLocalVideoStreamReady 2 " + target.videoView?.id + " " + (target.videoView == localStreamView) + " " + target.name
        )
    }

    private fun updateControlUI(){
        if (viewModel.currentCallLiveData?.haveOngoingCall() == true) {
            if(viewModel.isVideoCall()){
                binding.videoActionButtonContainer.visibility = View.VISIBLE
                binding.actionButtonContainer.visibility = View.GONE
            }else {
                binding.videoActionButtonContainer.visibility = View.GONE
                binding.actionButtonContainer.visibility = View.VISIBLE
            }

            val activeParticipant = viewModel.currentParticipants.filter {
                val participantState = viewModel.currentCallLiveData!!.getStateFor(it.id)
                (participantState == CallState.ONGOING || participantState == CallState.RINGING || participantState == CallState.READY)
            }

            binding.addContactButton.visibility = if(activeParticipant.size >= 7) View.INVISIBLE else View.VISIBLE
            binding.videoCallButton.visibility = if(activeParticipant.size > 2) View.GONE else View.VISIBLE
            binding.videoCallButtonText.visibility = if(activeParticipant.size > 2) View.GONE else View.VISIBLE

        }else{
            binding.actionButtonContainer.visibility = View.GONE
            binding.videoActionButtonContainer.visibility = View.GONE
            binding.addContactButton.visibility = View.INVISIBLE
        }

    }

    override fun onBackPressed() {
        //disable
    }

    fun onTap(v: View?) {
        if (viewModel.isVideoCall()){
            if (viewOnTap) {
                viewOnTap = false
                onHiddenControl()
                handler.removeCallbacksAndMessages(null)
            } else {
                viewOnTap = true
                onShowControl()
                handler.postDelayed({
                    viewOnTap = false
                    onHiddenControl()
                    handler.removeCallbacksAndMessages(null)
                }, 3000)
            }
        }
    }

    fun onHiddenControl() {
        binding.declineButton
                .animate()
                .translationY(binding.declineButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.declineButton.visibility = View.INVISIBLE
                    }
                })

        binding.switchCameraButton
                .animate()
                .translationY(binding.switchCameraButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.switchCameraButton.visibility = View.INVISIBLE
                    }
                })

        binding.muteCamVideoButton
                .animate()
                .translationY(binding.muteCamVideoButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteCamVideoButton.visibility = View.INVISIBLE
                    }
                })

        binding.muteMicVideoButton
                .animate()
                .translationY(binding.muteMicVideoButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteMicVideoButton.visibility = View.INVISIBLE
                    }
                })

        binding.shareScreenButton
                .animate()
                .translationY(binding.shareScreenButton.height.toFloat())
                .alpha(1.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.shareScreenButton.visibility = View.INVISIBLE
                    }
                })
    }

    fun onShowControl() {
        binding.declineButton.visibility =  View.VISIBLE
        binding.declineButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.declineButton.visibility =  View.VISIBLE
                    }
                })

        binding.switchCameraButton.visibility =  View.VISIBLE
        binding.switchCameraButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.switchCameraButton.visibility =  View.VISIBLE
                    }
                })

        binding.muteCamVideoButton.visibility =  View.VISIBLE
        binding.muteCamVideoButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteCamVideoButton.visibility =  View.VISIBLE
                    }
                })

        binding.muteMicVideoButton.visibility =  View.VISIBLE
        binding.muteMicVideoButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.muteMicVideoButton.visibility =  View.VISIBLE
                    }
                })

        binding.shareScreenButton.visibility =  View.VISIBLE
        binding.shareScreenButton.animate()
                .translationY(0.0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        binding.shareScreenButton.visibility =  View.VISIBLE
                    }
                })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == REQUEST_ADD_CALL_PARTICIPANT && resultCode == RESULT_OK && data != null){
            val addedID = data.getIntExtra(AddCallActivity.RESULT_ADD_CALL_PARTICIPANT_ID, 0)
            val addedName = data.getStringExtra(AddCallActivity.RESULT_ADD_CALL_PARTICIPANT_NAME)
            val addedAvatar = data.getStringExtra(AddCallActivity.RESULT_ADD_CALL_PARTICIPANT_AVATAR)

            if(addedID != 0 && addedName != null && addedAvatar != null){
                val newParticipant =
                    CallParticipantModel(id = addedID, name = addedName, avatar = addedAvatar)
                viewModel.inviteParticipant(newParticipant)
                callTimer.start()
            }

        }
    }

    companion object {
        const val CALL_PARAM_PARTICIPANTS = "call_participants"
        const val CALL_PARAM_TYPE = "call_type"
        const val CALL_PARAM_DIRECTION = "call_direction"
        private const val PERMISSION_REQ_ID_CALL = 22
        private const val REQUEST_ADD_CALL_PARTICIPANT = 133
    }

}