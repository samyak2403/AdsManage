package com.example.adsmanage.AdManage

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.viewbinding.ViewBinding
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import com.google.android.gms.ads.initialization.AdapterStatus
import kotlinx.coroutines.CoroutineExceptionHandler

class Helper(
    private val activity: Activity,
    private val binding: ViewBinding
) {
    /** Ad Properties - Holds references to different types of ads */
    private var mInterstitialAd: InterstitialAd? = null
    private var mRewardedInterstitialAd: RewardedInterstitialAd? = null
    private var mBannerAd: AdView? = null

    /** State Management - Handles all state-related properties */
    private var adState = AdState()
    
    data class AdState(
        var isAdLoading: Boolean = false,
        var isRewardedAdLoading: Boolean = false,
        var isNetworkAvailable: Boolean = false,
        var isAdMobInitialized: Boolean = false,
        var adLoadingRetryCount: Int = 0,
        var clickCount: Int = 0
    )

    // Configuration Properties
    private val config = AdConfig(
        retryDelay = 60000L,
        maxRetryAttempts = 3,
        clickResetDelay = 300000L,
        minClickThreshold = 2
    )

    data class AdConfig(
        val retryDelay: Long,
        val maxRetryAttempts: Int,
        val clickResetDelay: Long,
        val minClickThreshold: Int
    )

    // Error Tracking Properties
    private var lastError: AdResult.Error? = null
    private val errorTracker = AdErrorTracker()

    class AdErrorTracker {
        private val errors = mutableListOf<AdResult.Error>()
        
        fun trackError(error: AdResult.Error) {
            errors.add(error)
            if (errors.size > 10) errors.removeAt(0)
        }
        
        fun getRecentErrors() = errors.toList()
        fun clearErrors() = errors.clear()
    }

    // Network Utility
    private val networkManager by lazy {
        NetworkManager(activity)
    }

    private class NetworkManager(private val context: Context) {
        fun isNetworkAvailable(): Boolean {
            val connectivityManager = 
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    // Add new sealed class for Ad States
    sealed class AdResult {
        data class Success(val message: String) : AdResult()
        data class Error(val message: String, val code: Int? = null) : AdResult()
    }

    // Add error tracking
    private val errorHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "Error in ad operation: ${exception.message}", exception)
    }

    companion object {
        private const val TAG = "AdHelper"
        private const val RETRY_DELAY = 60000L // 1 minute
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val CLICK_RESET_DELAY = 300000L // 5 minutes
    }

    init {
        checkNetworkAndInitialize()
    }

    // Improved network check
    private fun checkNetworkAndInitialize(): AdResult {
        return try {
            val connectivityManager = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            when {
                network == null -> {
                    adState.isNetworkAvailable = false
                    AdResult.Error("No active network", 1001)
                }
                capabilities == null -> {
                    adState.isNetworkAvailable = false
                    AdResult.Error("No network capabilities", 1002)
                }
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> {
                    adState.isNetworkAvailable = false
                    AdResult.Error("No internet connection", 1003)
                }
                else -> {
                    adState.isNetworkAvailable = true
                    initializeAdMob()
                    AdResult.Success("Network available")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network check failed: ${e.message}", e)
            AdResult.Error("Network check failed: ${e.message}", 1004)
        }
    }

    private fun initializeAdMob() {
        try {
            MobileAds.initialize(activity) { initializationStatus ->
                val statusMap = initializationStatus.adapterStatusMap
                adState.isAdMobInitialized = true  // Set to true when initialized
                
                statusMap.forEach { (adapterClass, status) ->
                    if (status.initializationState != AdapterStatus.State.READY) {
                        adState.isAdMobInitialized = false  // Set to false if any adapter fails
                        Log.e(TAG, "Adapter $adapterClass failed to initialize: ${status.description}")
                    }
                }

                if (adState.isAdMobInitialized) {
                    Log.d(TAG, "AdMob initialized successfully")
                    preloadAllAds()
                } else {
                    Log.e(TAG, "AdMob initialization incomplete")
                    retryInitialization()
                }
            }
        } catch (e: Exception) {
            adState.isAdMobInitialized = false
            Log.e(TAG, "Error initializing AdMob: ${e.message}", e)
        }
    }

    private fun retryInitialization() {
        if (adState.adLoadingRetryCount < MAX_RETRY_ATTEMPTS) {
            adState.adLoadingRetryCount++
            activity.window?.decorView?.postDelayed({
                checkNetworkAndInitialize()
            }, RETRY_DELAY)
        }
    }

    private fun preloadAllAds() {
        if (!adState.isNetworkAvailable) {
            Log.e(TAG, "Cannot preload ads: No network connection")
            return
        }
        prepareInterstitialAd()
        prepareRewardedInterstitialAd()
    }

    // Improved ad loading with validation
    private fun prepareInterstitialAd(): AdResult {
        if (adState.isAdLoading || mInterstitialAd != null) {
            return AdResult.Error("Ad already loading or loaded", 2001)
        }

        return try {
            adState.isAdLoading = true
            val adRequest = AdRequest.Builder().build()
            
            InterstitialAd.load(
                activity,
                activity.getString(com.example.adsmanage.R.string.admob_interstitial_id),
                adRequest,
                createInterstitialCallback()
            )
            AdResult.Success("Ad load initiated")
        } catch (e: Exception) {
            handleAdError(e, "Failed to load interstitial ad")
        }
    }

    private fun handleAdError(
        exception: Exception, 
        message: String, 
        code: Int = 1000
    ): AdResult.Error {
        adState.isAdLoading = false
        val error = AdResult.Error("$message: ${exception.message}", code)
        lastError = error
        errorTracker.trackError(error)
        Log.e(TAG, message, exception)
        return error
    }

    private fun createInterstitialCallback(): InterstitialAdLoadCallback {
        return object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(interstitialAd: InterstitialAd) {
                adState.isAdLoading = false
                mInterstitialAd = interstitialAd
                Log.d(TAG, "Interstitial ad loaded successfully")
                setFullScreenContentCallback()
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                adState.isAdLoading = false
                mInterstitialAd = null
                Log.e(TAG, "Failed to load interstitial ad: ${loadAdError.message}")
                // Retry loading after delay if failed
                activity.window?.decorView?.postDelayed({ prepareInterstitialAd() }, 60000)
            }
        }
    }

    private fun setFullScreenContentCallback() {
        mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdClicked() {
                Log.d(TAG, "Ad clicked")
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Ad dismissed")
                mInterstitialAd = null
                prepareInterstitialAd() // Reload ad after dismissal
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(TAG, "Failed to show ad: ${adError.message}")
                mInterstitialAd = null
                prepareInterstitialAd()
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Ad showed successfully")
                mInterstitialAd = null
            }

            override fun onAdImpression() {
                Log.d(TAG, "Ad impression recorded")
            }
        }
    }

    // Improved ad showing with validation
    fun showInterstitialAd(
        onAdResult: (AdResult) -> Unit
    ) {
        when {
            !adState.isNetworkAvailable -> {
                onAdResult(AdResult.Error("No network connection", 4001))
            }
            mInterstitialAd == null -> {
                onAdResult(AdResult.Error("Ad not loaded", 4002))
                prepareInterstitialAd()
            }
            else -> {
                try {
                    mInterstitialAd?.show(activity)
                    onAdResult(AdResult.Success("Ad shown successfully"))
                } catch (e: Exception) {
                    val error = AdResult.Error("Failed to show ad: ${e.message}", 4003)
                    onAdResult(error)
                    Log.e(TAG, "Show ad error: ${e.message}", e)
                }
            }
        }
    }

    fun showCounterInterstitialAd(
        threshold: Int = config.minClickThreshold,
        onAdShown: (() -> Unit)? = null,
        onAdNotShown: ((String) -> Unit)? = null
    ) {
        incrementClickCounter()
        
        if (adState.clickCount >= threshold) {
            showInterstitialAd { result ->
                when (result) {
                    is AdResult.Success -> {
                        resetClickCounter()
                        onAdShown?.invoke()
                    }
                    is AdResult.Error -> {
                        onAdNotShown?.invoke(result.message)
                    }
                }
            }
        }
    }

    private fun incrementClickCounter() {
        adState.clickCount++
        scheduleClickCounterReset()
    }

    private fun resetClickCounter() {
        adState.clickCount = 0
    }

    private fun scheduleClickCounterReset() {
        activity.window?.decorView?.postDelayed({
            if (adState.clickCount > 0) {
                resetClickCounter()
                Log.d(TAG, "Click counter reset due to inactivity")
            }
        }, config.clickResetDelay)
    }

    // Rewarded Interstitial Ad Methods
    private fun prepareRewardedInterstitialAd() {
        if (adState.isRewardedAdLoading || mRewardedInterstitialAd != null) return

        adState.isRewardedAdLoading = true
        RewardedInterstitialAd.load(
            activity,
            activity.getString(com.example.adsmanage.R.string.admob_rewarded_interstitial_id),
            AdRequest.Builder().build(),
            object : RewardedInterstitialAdLoadCallback() {
                override fun onAdLoaded(rewardedAd: RewardedInterstitialAd) {
                    adState.isRewardedAdLoading = false
                    mRewardedInterstitialAd = rewardedAd
                    Log.d(TAG, "Rewarded interstitial ad loaded successfully")
                    setRewardedAdCallback()
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    adState.isRewardedAdLoading = false
                    mRewardedInterstitialAd = null
                    Log.e(TAG, "Failed to load rewarded interstitial ad: ${loadAdError.message}")
                    // Retry loading after delay
                    activity.window?.decorView?.postDelayed({ prepareRewardedInterstitialAd() }, 60000)
                }
            }
        )
    }

    private fun setRewardedAdCallback() {
        mRewardedInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdClicked() {
                Log.d(TAG, "Rewarded ad clicked")
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Rewarded ad dismissed")
                mRewardedInterstitialAd = null
                prepareRewardedInterstitialAd() // Reload ad after dismissal
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(TAG, "Failed to show rewarded ad: ${adError.message}")
                mRewardedInterstitialAd = null
                prepareRewardedInterstitialAd()
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Rewarded ad showed successfully")
            }

            override fun onAdImpression() {
                Log.d(TAG, "Rewarded ad impression recorded")
            }
        }
    }

    fun showRewardedInterstitialAd(
        onRewarded: ((RewardItem) -> Unit)? = null,
        onAdNotReady: (() -> Unit)? = null
    ) {
        if (mRewardedInterstitialAd != null) {
            mRewardedInterstitialAd?.show(activity) { rewardItem ->
                val rewardAmount = rewardItem.amount
                val rewardType = rewardItem.type
                Log.d(TAG, "User earned reward: $rewardAmount $rewardType")
                onRewarded?.invoke(rewardItem)
            }
        } else {
            Log.w(TAG, "Rewarded ad not ready to show")
            onAdNotReady?.invoke()
            prepareRewardedInterstitialAd()
        }
    }

    fun showCounterRewardedAd(
        threshold: Int,
        onRewarded: ((RewardItem) -> Unit)? = null,
        onAdShown: (() -> Unit)? = null,
        onAdNotShown: (() -> Unit)? = null
    ) {
        adState.clickCount++
        if (adState.clickCount >= threshold) {
            if (mRewardedInterstitialAd != null) {
                showRewardedInterstitialAd(
                    onRewarded = { rewardItem ->
                        onRewarded?.invoke(rewardItem)
                        onAdShown?.invoke()
                    },
                    onAdNotReady = {
                        onAdNotShown?.invoke()
                    }
                )
                adState.clickCount = 0
            } else {
                onAdNotShown?.invoke()
            }
        }
    }

    // Banner Ad Methods
    fun loadBannerAd(
        linearLayoutId: Int,
        onAdLoaded: ((AdResult) -> Unit)? = null
    ) {
        val result = validateBannerAdPrerequisites(linearLayoutId)
        if (result is AdResult.Error) {
            onAdLoaded?.invoke(result)
            return
        }

        try {
            setupBannerAd(linearLayoutId, onAdLoaded)
        } catch (e: Exception) {
            val error = AdResult.Error("Banner ad setup failed: ${e.message}", 3001)
            onAdLoaded?.invoke(error)
            Log.e(TAG, "Banner ad error: ${e.message}", e)
        }
    }

    private fun validateBannerAdPrerequisites(linearLayoutId: Int): AdResult {
        if (!adState.isNetworkAvailable) {
            return AdResult.Error("No network connection", 3002)
        }

        if (!adState.isAdMobInitialized) {
            return AdResult.Error("AdMob not initialized", 3004)
        }

        val adContainer = activity.findViewById<LinearLayout>(linearLayoutId)
        return when {
            adContainer == null -> AdResult.Error("Banner container not found", 3003)
            else -> AdResult.Success("Prerequisites validated")
        }
    }

    private fun setupBannerAd(linearLayoutId: Int, onAdLoaded: ((AdResult) -> Unit)? = null) {
        try {
            val adContainer = activity.findViewById<LinearLayout>(linearLayoutId)
            if (adContainer == null) {
                onAdLoaded?.invoke(AdResult.Error("Banner container not found", 3003))
                return
            }

            adContainer.removeAllViews()
            mBannerAd?.destroy()

            mBannerAd = AdView(activity).apply {
                adUnitId = activity.getString(com.example.adsmanage.R.string.admob_banner_id)
                setAdSize(getAdaptiveBannerAdSize(adContainer))
                adListener = createBannerAdListener(onAdLoaded)
            }

            adContainer.addView(mBannerAd)
            mBannerAd?.loadAd(AdRequest.Builder().build())

            onAdLoaded?.invoke(AdResult.Success("Banner ad loaded successfully"))
        } catch (e: Exception) {
            val error = AdResult.Error("Error loading banner ad: ${e.message}", 3001)
            onAdLoaded?.invoke(error)
            Log.e(TAG, "Banner ad error: ${e.message}", e)
        }
    }

    private fun getAdaptiveBannerAdSize(adContainer: LinearLayout): AdSize {
        try {
            // Get display metrics
            val displayMetrics = activity.resources.displayMetrics
            
            // Calculate ad width
            var adWidthPixels = adContainer.width.toFloat()
            if (adWidthPixels == 0f) {
                adWidthPixels = displayMetrics.widthPixels.toFloat()
            }

            // Convert to density-independent pixels
            val adWidth = (adWidthPixels / displayMetrics.density).toInt()

            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating ad size: ${e.message}", e)
            // Fallback to standard banner size
            return AdSize.BANNER
        }
    }

    private fun createBannerAdListener(
        onAdLoaded: ((AdResult) -> Unit)?
    ): AdListener {
        return object : AdListener() {
            override fun onAdLoaded() {
                Log.d(TAG, "Banner ad loaded successfully")
                onAdLoaded?.invoke(AdResult.Success("Banner ad loaded successfully"))
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                val errorMessage = "Banner ad failed to load: ${loadAdError.message}"
                Log.e(TAG, errorMessage)
                onAdLoaded?.invoke(AdResult.Error(errorMessage))
                retryLoadingBannerAd()
            }

            override fun onAdClicked() {
                Log.d(TAG, "Banner ad clicked")
            }

            override fun onAdImpression() {
                Log.d(TAG, "Banner ad impression recorded")
            }

            override fun onAdOpened() {
                Log.d(TAG, "Banner ad opened")
            }

            override fun onAdClosed() {
                Log.d(TAG, "Banner ad closed")
            }
        }
    }

    private fun retryLoadingBannerAd() {
        activity.window?.decorView?.postDelayed({
            mBannerAd?.loadAd(AdRequest.Builder().build())
        }, RETRY_DELAY)
    }

    // Cleanup with error handling
    fun destroy() {
        try {
            mBannerAd?.destroy()
            mBannerAd = null
            mInterstitialAd = null
            mRewardedInterstitialAd = null
            resetClickCounter()
            adState.adLoadingRetryCount = 0
            errorTracker.clearErrors()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }

    // Method to check if any ad is ready
    fun isAnyAdReady(): Boolean {
        return mInterstitialAd != null || 
               mRewardedInterstitialAd != null || 
               mBannerAd != null
    }

    // Add this method to preload all ad types
    fun preloadAds() {
        if (!adState.isNetworkAvailable) {
            Log.e(TAG, "Cannot preload ads: No network connection")
            return
        }

        try {
            // Load interstitial ad if not already loading or loaded
            if (mInterstitialAd == null && !adState.isAdLoading) {
                prepareInterstitialAd()
            }

            // Load rewarded ad if not already loading or loaded
            if (mRewardedInterstitialAd == null && !adState.isRewardedAdLoading) {
                prepareRewardedInterstitialAd()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading ads: ${e.message}", e)
        }
    }

    // Add diagnostic methods
    fun getAdStatus(): AdStatusReport {
        return AdStatusReport(
            isNetworkAvailable = adState.isNetworkAvailable,
            isAdMobInitialized = adState.isAdMobInitialized,
            interstitialAdReady = mInterstitialAd != null,
            rewardedAdReady = mRewardedInterstitialAd != null,
            bannerAdReady = mBannerAd != null,
            clickCount = adState.clickCount,
            retryCount = adState.adLoadingRetryCount,
            recentErrors = errorTracker.getRecentErrors()
        )
    }

    data class AdStatusReport(
        val isNetworkAvailable: Boolean,
        val isAdMobInitialized: Boolean,
        val interstitialAdReady: Boolean,
        val rewardedAdReady: Boolean,
        val bannerAdReady: Boolean,
        val clickCount: Int,
        val retryCount: Int,
        val recentErrors: List<AdResult.Error>
    )

    // Add recovery methods
    fun recoverFromError() {
        adState.adLoadingRetryCount = 0
        lastError = null
        checkNetworkAndInitialize()
    }
}