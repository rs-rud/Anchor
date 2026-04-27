package com.example.anchor

import android.content.Context
import android.content.SharedPreferences
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.IOException
import java.util.Locale

class GeofenceFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private lateinit var geofenceManager: GeofenceManager

    private val addressSearchHandler = Handler(Looper.getMainLooper())
    private var addressDebounce: Runnable? = null
    private var addressSearchSerial = 0
    private var selectedNominatim: NominatimAddressResult? = null
    private val addressPopupData = ArrayList<NominatimAddressResult>()
    private var addressListPopup: ListPopupWindow? = null
    private var addressPopupAdapter: ArrayAdapter<NominatimAddressResult>? = null
    private var addressTextWatcher: TextWatcher? = null
    private var addressSkipDebounce: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_geofence, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        geofenceManager = GeofenceManager(requireContext())

        val tilAddress = view.findViewById<TextInputLayout>(R.id.tilAddress)
        val etAddress = view.findViewById<TextInputEditText>(R.id.etAddress)
        val sliderRadius = view.findViewById<Slider>(R.id.sliderRadius)
        val tvRadiusLabel = view.findViewById<TextView>(R.id.tvRadiusLabel)
        val btnSet = view.findViewById<MaterialButton>(R.id.btnSetGeofence)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveGeofence)
        val tvStatusValue = view.findViewById<TextView>(R.id.tvStatusValue)
        val insideOutsideContainer = view.findViewById<View>(R.id.insideOutsideContainer)
        val tvInsideOutside = view.findViewById<TextView>(R.id.tvInsideOutside)
        val tvInsideOutsideDot = view.findViewById<View>(R.id.tvInsideOutsideDot)
        val heroStatusDot = view.findViewById<View>(R.id.heroStatusDot)
        val heroStatusLabel = view.findViewById<TextView>(R.id.heroStatusLabel)

        sliderRadius.addOnChangeListener { _, value, _ ->
            tvRadiusLabel.text = formatRadiusValue(value.toInt())
        }
        tvRadiusLabel.text = formatRadiusValue(sliderRadius.value.toInt())

        setupAddressAutocomplete(tilAddress, etAddress)

        btnSet.setOnClickListener {
            val address = etAddress.text?.toString()?.trim()
            if (address.isNullOrBlank()) {
                etAddress.error = getString(R.string.address_required)
                return@setOnClickListener
            }

            val radiusMeters = sliderRadius.value
            val fromSuggestion = selectedNominatim
                .takeIf { it.displayName == address }
            resolveAndSetGeofence(
                address,
                fromSuggestion,
                radiusMeters,
                tvStatusValue,
                insideOutsideContainer,
                tvInsideOutside,
                tvInsideOutsideDot,
                btnRemove,
                heroStatusDot,
                heroStatusLabel
            )
        }

        btnRemove.setOnClickListener {
            geofenceManager.removeGeofence(
                onSuccess = {
                    requireActivity().runOnUiThread {
                        tvStatusValue.text = getString(R.string.no_geofence_set)
                        insideOutsideContainer.visibility = View.GONE
                        btnRemove.visibility = View.GONE
                        heroStatusDot.setBackgroundResource(R.drawable.bg_status_dot_idle)
                        heroStatusLabel.setText(R.string.hero_idle_label)
                        Toast.makeText(requireContext(), R.string.geofence_removed, Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { e ->
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.geofence_remove_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        refreshStatus(
            tvStatusValue,
            insideOutsideContainer,
            tvInsideOutside,
            tvInsideOutsideDot,
            btnRemove,
            heroStatusDot,
            heroStatusLabel
        )
    }

    override fun onResume() {
        super.onResume()
        val root = view ?: return
        val tvStatusValue = root.findViewById<TextView>(R.id.tvStatusValue) ?: return
        val insideOutsideContainer = root.findViewById<View>(R.id.insideOutsideContainer) ?: return
        val tvInsideOutside = root.findViewById<TextView>(R.id.tvInsideOutside) ?: return
        val tvInsideOutsideDot = root.findViewById<View>(R.id.tvInsideOutsideDot) ?: return
        val btnRemove = root.findViewById<MaterialButton>(R.id.btnRemoveGeofence) ?: return
        val heroStatusDot = root.findViewById<View>(R.id.heroStatusDot) ?: return
        val heroStatusLabel = root.findViewById<TextView>(R.id.heroStatusLabel) ?: return
        refreshStatus(
            tvStatusValue,
            insideOutsideContainer,
            tvInsideOutside,
            tvInsideOutsideDot,
            btnRemove,
            heroStatusDot,
            heroStatusLabel
        )
    }

    override fun onDestroyView() {
        addressDebounce?.let { addressSearchHandler.removeCallbacks(it) }
        addressDebounce = null
        addressListPopup?.dismiss()
        addressListPopup = null
        addressPopupAdapter = null
        val et = view?.findViewById<TextInputEditText>(R.id.etAddress)
        addressTextWatcher?.let { et?.removeTextChangedListener(it) }
        addressTextWatcher = null
        super.onDestroyView()
    }

    private fun setupAddressAutocomplete(
        tilAddress: TextInputLayout,
        etAddress: TextInputEditText
    ) {
        val popup = ListPopupWindow(requireContext())
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_address_dropdown,
            android.R.id.text1,
            addressPopupData
        )
        addressPopupAdapter = adapter
        popup.setAdapter(adapter)
        popup.isModal = true
        popup.setOnItemClickListener { _, _, pos, _ ->
            val r = addressPopupData.getOrNull(pos) ?: return@setOnItemClickListener
            selectedNominatim = r
            addressSkipDebounce = true
            etAddress.setText(r.displayName)
            etAddress.setSelection(r.displayName.length)
            etAddress.error = null
            popup.dismiss()
        }
        addressListPopup = popup

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                if (addressSkipDebounce) {
                    addressSkipDebounce = false
                    return
                }
                if (selectedNominatim != null && text != selectedNominatim?.displayName) {
                    selectedNominatim = null
                }
                addressDebounce?.let { addressSearchHandler.removeCallbacks(it) }
                if (text.trim().length < 3) {
                    addressListPopup?.dismiss()
                    return
                }
                val run = Runnable {
                    runAddressSearch(text.trim().toString(), etAddress, tilAddress)
                }
                addressDebounce = run
                addressSearchHandler.postDelayed(run, 500L)
            }
        }
        addressTextWatcher = watcher
        etAddress.addTextChangedListener(watcher)
    }

    private fun runAddressSearch(
        query: String,
        etAddress: TextInputEditText,
        tilAddress: TextInputLayout
    ) {
        if (query.length < 3) return
        val serial = ++addressSearchSerial
        Thread {
            val list = NominatimAddressSearch.search(query)
            requireActivity().runOnUiThread {
                if (serial != addressSearchSerial) return@runOnUiThread
                if (etAddress.text?.toString()?.trim() != query) return@runOnUiThread
                addressPopupData.clear()
                addressPopupData.addAll(list)
                addressPopupAdapter?.notifyDataSetChanged()
                val popup = addressListPopup ?: return@runOnUiThread
                popup.anchorView = tilAddress
                val w = tilAddress.width
                if (w > 0) popup.setContentWidth(w) else {
                    tilAddress.post {
                        if (tilAddress.width > 0) {
                            addressListPopup?.setContentWidth(tilAddress.width)
                        }
                    }
                }
                if (addressPopupData.isEmpty()) {
                    popup.dismiss()
                } else if (etAddress.isFocused) {
                    popup.show()
                }
            }
        }.start()
    }

    private fun formatRadiusValue(radius: Int): String = "${radius} m"

    private fun refreshStatus(
        tvStatusValue: TextView,
        insideOutsideContainer: View,
        tvInsideOutside: TextView,
        tvInsideOutsideDot: View,
        btnRemove: MaterialButton,
        heroStatusDot: View,
        heroStatusLabel: TextView
    ) {
        val active = prefs.getBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)
        if (active) {
            val address = prefs.getString(AnchorPrefs.KEY_GEOFENCE_ADDRESS, "") ?: ""
            val radius = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_RADIUS, 0f).toInt()
            tvStatusValue.text = getString(R.string.geofence_active_format, address, radius)
            btnRemove.visibility = View.VISIBLE

            heroStatusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
            heroStatusLabel.setText(R.string.hero_active_label)

            val inside = prefs.getBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, false)
            insideOutsideContainer.visibility = View.VISIBLE
            if (inside) {
                tvInsideOutside.setText(R.string.inside_geofence)
                tvInsideOutsideDot.setBackgroundResource(R.drawable.bg_status_dot_active)
            } else {
                tvInsideOutside.setText(R.string.outside_geofence)
                tvInsideOutsideDot.setBackgroundResource(R.drawable.bg_status_dot_idle)
            }
        } else {
            tvStatusValue.text = getString(R.string.no_geofence_set)
            insideOutsideContainer.visibility = View.GONE
            btnRemove.visibility = View.GONE
            heroStatusDot.setBackgroundResource(R.drawable.bg_status_dot_idle)
            heroStatusLabel.setText(R.string.hero_idle_label)
        }
    }

    private fun resolveAndSetGeofence(
        address: String,
        fromSuggestion: NominatimAddressResult?,
        radiusMeters: Float,
        tvStatusValue: TextView,
        insideOutsideContainer: View,
        tvInsideOutside: TextView,
        tvInsideOutsideDot: View,
        btnRemove: MaterialButton,
        heroStatusDot: View,
        heroStatusLabel: TextView
    ) {
        if (fromSuggestion != null) {
            prefs.edit().putString(AnchorPrefs.KEY_GEOFENCE_ADDRESS, address).apply()
            geofenceManager.addGeofence(
                lat = fromSuggestion.latitude,
                lng = fromSuggestion.longitude,
                radiusMeters = radiusMeters,
                onSuccess = {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), R.string.geofence_set_success, Toast.LENGTH_SHORT).show()
                        refreshStatus(
                            tvStatusValue,
                            insideOutsideContainer,
                            tvInsideOutside,
                            tvInsideOutsideDot,
                            btnRemove,
                            heroStatusDot,
                            heroStatusLabel
                        )
                    }
                },
                onFailure = { e ->
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.geofence_set_failed, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
            return
        }

        Thread {
            try {
                val geocoder = Geocoder(requireContext(), Locale.getDefault())
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocationName(address, 1)

                if (results.isNullOrEmpty()) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), R.string.address_not_found, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val location = results[0]
                val lat = location.latitude
                val lng = location.longitude

                prefs.edit().putString(AnchorPrefs.KEY_GEOFENCE_ADDRESS, address).apply()

                geofenceManager.addGeofence(
                    lat = lat,
                    lng = lng,
                    radiusMeters = radiusMeters,
                    onSuccess = {
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), R.string.geofence_set_success, Toast.LENGTH_SHORT).show()
                            refreshStatus(
                                tvStatusValue,
                                insideOutsideContainer,
                                tvInsideOutside,
                                tvInsideOutsideDot,
                                btnRemove,
                                heroStatusDot,
                                heroStatusLabel
                            )
                        }
                    },
                    onFailure = { e ->
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.geofence_set_failed, e.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            } catch (e: IOException) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), R.string.geocoder_error, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
