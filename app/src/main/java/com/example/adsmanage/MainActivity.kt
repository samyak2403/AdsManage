package com.example.adsmanage

import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.adsmanage.AdManage.Helper
import com.example.adsmanage.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.initialization.AdapterStatus

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adHelper: Helper
    private var bannerAd: AdView? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Mobile Ads SDK
        MobileAds.initialize(this) { initializationStatus ->
            val statusMap = initializationStatus.adapterStatusMap
            for ((adapterClass, status) in statusMap) {
                when (status.initializationState) {
                    AdapterStatus.State.NOT_READY -> {
                        Log.e(TAG, "Adapter: $adapterClass is not ready.")
                    }
                    AdapterStatus.State.READY -> {
                        Log.d(TAG, "Adapter: $adapterClass is ready.")
                        // Load banner ad once SDK is initialized
                        loadBannerAd()
                    }
                }
            }
        }

        setupAds()
        setupClickListeners()
    }

    private fun setupAds() {
        // Initialize ad helper
        adHelper = Helper(this, binding)

        // Load banner ad
        adHelper.loadBannerAd(
            linearLayoutId = R.id.bannerContainer
        ) { result ->
            when (result) {
                is Helper.AdResult.Success -> {
                    Log.d(TAG, "Banner loaded: ${result.message}")
                }
                is Helper.AdResult.Error -> {
                    Log.e(TAG, "Banner error: ${result.message}")
                    handleAdError(result)
                }
            }
        }

        // Preload other ad types
        adHelper.preloadAds()
    }

    private fun setupClickListeners() {
        binding.apply {
            // Normal interstitial button
            btnShowInterstitial.setOnClickListener {
                showNormalInterstitial()
            }

            // Counter-based interstitial button
            btnShowCounterInterstitial.setOnClickListener {
                showCounterInterstitial()
            }

            // Rewarded ad button
            btnShowRewarded.setOnClickListener {
                showRewardedAd()
            }
        }
    }

    private fun showNormalInterstitial() {
        adHelper.showInterstitialAd { result ->
            when (result) {
                is Helper.AdResult.Success -> {
                    showToast("Ad shown successfully")
                }
                is Helper.AdResult.Error -> {
                    showToast("Failed to show ad: ${result.message}")
                    handleAdError(result)
                }
            }
        }
    }

    private fun showCounterInterstitial() {
        adHelper.showCounterInterstitialAd(
            threshold = 2,  // Show ad every 2 clicks
            onAdShown = {
                showToast("Counter ad shown")
            },
            onAdNotShown = { reason ->
                showToast("Counter ad not shown: $reason")
            }
        )
    }

    private fun showRewardedAd() {
        adHelper.showRewardedInterstitialAd(
            onRewarded = { reward ->
                showToast("Earned reward: ${reward.amount} ${reward.type}")
                // Handle reward here (e.g., give user coins, points, etc.)
            },
            onAdNotReady = {
                showToast("Rewarded ad not ready")
            }
        )
    }

    private fun handleAdError(error: Helper.AdResult.Error) {
        when (error.code) {
            1001, 1002, 1003 -> {
                // Network related errors
                showNetworkErrorDialog()
            }
            2001, 2002 -> {
                // Ad loading errors
                adHelper.recoverFromError()
            }
            3001, 3002, 3003, 3004 -> {
                // Banner ad errors
                Log.e(TAG, "Banner error: ${error.message}")
            }
            4001, 4002, 4003 -> {
                // Show ad errors
                adHelper.preloadAds()
            }
        }
    }

    private fun showNetworkErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle("Network Error")
            .setMessage("Please check your internet connection")
            .setPositiveButton("Retry") { _, _ ->
                adHelper.recoverFromError()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        binding.adContainer.shimmerViewContainer.startShimmer()
        // Reload ads if needed
        if (::adHelper.isInitialized && !adHelper.isAnyAdReady()) {
            adHelper.preloadAds()
        }
    }

    override fun onPause() {
        binding.adContainer.shimmerViewContainer.stopShimmer()
        super.onPause()
    }

    private fun loadBannerAd() {
        try {
            // Start shimmer effect
            binding.adContainer.shimmerViewContainer.startShimmer()
            binding.adContainer.shimmerViewContainer.visibility = View.VISIBLE
            binding.adContainer.adsContainerBanner.visibility = View.GONE
            binding.bannerContainer.visibility = View.GONE
            
            val adView = AdView(this)
            adView.adUnitId = getString(R.string.admob_banner_id)
            adView.setAdSize(getAdSize())
            
            binding.bannerContainer.removeAllViews()
            binding.bannerContainer.addView(adView)

            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)

            bannerAd = adView

            adView.adListener = object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e(TAG, "Banner ad failed to load: ${error.message}")
                    // Stop shimmer and show native banner
                    binding.adContainer.shimmerViewContainer.stopShimmer()
                    binding.adContainer.shimmerViewContainer.visibility = View.GONE
                    binding.adContainer.adsContainerBanner.visibility = View.VISIBLE
                    binding.bannerContainer.visibility = View.GONE
                }
                
                override fun onAdLoaded() {
                    Log.d(TAG, "Banner ad loaded successfully")
                    // Stop shimmer and show banner ad
                    binding.adContainer.shimmerViewContainer.stopShimmer()
                    binding.adContainer.root.visibility = View.GONE
                    binding.bannerContainer.visibility = View.VISIBLE
                }

                override fun onAdOpened() {
                    Log.d(TAG, "Banner ad opened")
                }

                override fun onAdClosed() {
                    Log.d(TAG, "Banner ad closed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading banner ad: ${e.message}")
            // Stop shimmer and show native banner
            binding.adContainer.shimmerViewContainer.stopShimmer()
            binding.adContainer.shimmerViewContainer.visibility = View.GONE
            binding.adContainer.adsContainerBanner.visibility = View.VISIBLE
            binding.bannerContainer.visibility = View.GONE
        }
    }

    private fun getAdSize(): AdSize {
        // Determine the screen width in pixels
        val display = windowManager.defaultDisplay
        val outMetrics = DisplayMetrics()
        display.getMetrics(outMetrics)

        val density = outMetrics.density
        var adWidthPixels = binding.bannerContainer.width.toFloat()

        // If the ad hasn't been laid out, default to the full screen width
        if (adWidthPixels == 0f) {
            adWidthPixels = outMetrics.widthPixels.toFloat()
        }

        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth)
    }

    override fun onDestroy() {
        bannerAd?.destroy()
        if (::adHelper.isInitialized) {
            adHelper.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}