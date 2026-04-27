package com.example.anchor

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

class GeofenceFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private lateinit var geofenceManager: GeofenceManager

    private val addressSearchHandler = Handler(Looper.getMainLooper())
    private var addressDebounce: Runnable? = null
    private var addressSearchSerial = 0
    private var autocompleteSessionToken: AutocompleteSessionToken = AutocompleteSessionToken.newInstance()
    private var resolvedAddress: ResolvedGeofenceAddress? = null
    private val addressPopupData = ArrayList<AutocompleteRow>()
    private var addressListPopup: ListPopupWindow? = null
    private var addressPopupAdapter: ArrayAdapter<AutocompleteRow>? = null
    private var addressTextWatcher: TextWatcher? = null
    private var addressSkipDebounce: Boolean = false
    private var placesClient: PlacesClient? = null
    private var placesMissingNotified = false

    private var pendingUseCurrentLocation = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            if (pendingUseCurrentLocation) {
                pendingUseCurrentLocation = false
                view?.findViewById<TextInputEditText>(R.id.etAddress)?.let { fetchCurrentLocationIntoField(it) }
            }
        } else {
            pendingUseCurrentLocation = false
            Toast.makeText(requireContext(), R.string.location_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_geofence, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        geofenceManager = GeofenceManager(requireContext())
        placesClient = if (Places.isInitialized()) Places.createClient(requireContext()) else null

        val tilAddress = view.findViewById<TextInputLayout>(R.id.tilAddress)
        val etAddress = view.findViewById<TextInputEditText>(R.id.etAddress)
        val btnUseCurrentLocation = view.findViewById<MaterialButton>(R.id.btnUseCurrentLocation)
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

        btnUseCurrentLocation.setOnClickListener {
            if (hasLocationForCurrentPosition()) {
                fetchCurrentLocationIntoField(etAddress)
            } else {
                pendingUseCurrentLocation = true
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }

        btnSet.setOnClickListener {
            val address = etAddress.text?.toString()?.trim()
            if (address.isNullOrBlank()) {
                etAddress.error = getString(R.string.address_required)
                return@setOnClickListener
            }

            val radiusMeters = sliderRadius.value
            val resolved = resolvedAddress?.takeIf { it.label == address }
            resolveAndSetGeofence(
                address,
                resolved,
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
        placesClient = null
        placesMissingNotified = false
        val et = view?.findViewById<TextInputEditText>(R.id.etAddress)
        addressTextWatcher?.let { et?.removeTextChangedListener(it) }
        addressTextWatcher = null
        super.onDestroyView()
    }

    private fun hasLocationForCurrentPosition(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun fetchCurrentLocationIntoField(etAddress: TextInputEditText) {
        val fused = LocationServices.getFusedLocationProviderClient(requireContext())
        val cts = CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    applyReverseGeocode(loc, etAddress)
                } else {
                    fused.lastLocation.addOnSuccessListener { last ->
                        if (last != null) applyReverseGeocode(last, etAddress)
                        else toastCurrentLocationFailed()
                    }
                }
            }
            .addOnFailureListener {
                fused.lastLocation.addOnSuccessListener { last ->
                    if (last != null) applyReverseGeocode(last, etAddress)
                    else toastCurrentLocationFailed()
                }
            }
    }

    private fun toastCurrentLocationFailed() {
        Toast.makeText(requireContext(), R.string.current_location_failed, Toast.LENGTH_LONG).show()
    }

    private fun applyReverseGeocode(location: Location, etAddress: TextInputEditText) {
        Thread {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = try {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            } catch (_: Exception) {
                null
            }
            val label = addresses?.firstOrNull()?.getAddressLine(0)
                ?: getString(R.string.coordinates_fallback_format, location.latitude, location.longitude)
            requireActivity().runOnUiThread {
                resolvedAddress = ResolvedGeofenceAddress(
                    label,
                    location.latitude,
                    location.longitude,
                    fromGps = true
                )
                addressSkipDebounce = true
                etAddress.setText(label)
                etAddress.setSelection(label.length)
                etAddress.error = null
                autocompleteSessionToken = AutocompleteSessionToken.newInstance()
            }
        }.start()
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
            val row = addressPopupData.getOrNull(pos) ?: return@setOnItemClickListener
            val client = placesClient ?: return@setOnItemClickListener
            val placeId = row.prediction.placeId
            popup.dismiss()

            val fields = listOf(
                Place.Field.ID,
                Place.Field.LAT_LNG,
                Place.Field.FORMATTED_ADDRESS
            )
            val request = FetchPlaceRequest.builder(placeId, fields)
                .setSessionToken(autocompleteSessionToken)
                .build()
            client.fetchPlace(request)
                .addOnSuccessListener { response ->
                    val place = response.place
                    val latLng = place.latLng
                    if (latLng == null) {
                        Toast.makeText(requireContext(), R.string.address_not_found, Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }
                    val label = place.formattedAddress
                        ?: row.prediction.getFullText(null).toString()
                    resolvedAddress = ResolvedGeofenceAddress(
                        label,
                        latLng.latitude,
                        latLng.longitude,
                        fromGps = false
                    )
                    addressSkipDebounce = true
                    etAddress.setText(label)
                    etAddress.setSelection(label.length)
                    etAddress.error = null
                    autocompleteSessionToken = AutocompleteSessionToken.newInstance()
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), R.string.address_not_found, Toast.LENGTH_LONG).show()
                }
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
                if (text.isBlank() || text.trim().length < 3) {
                    autocompleteSessionToken = AutocompleteSessionToken.newInstance()
                }
                if (resolvedAddress != null && text != resolvedAddress?.label) {
                    resolvedAddress = null
                }
                addressDebounce?.let { addressSearchHandler.removeCallbacks(it) }
                if (text.trim().length < 3) {
                    addressListPopup?.dismiss()
                    return
                }
                val run = Runnable {
                    runAddressSearch(text.trim(), etAddress, tilAddress)
                }
                addressDebounce = run
                addressSearchHandler.postDelayed(run, 400L)
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
        val client = placesClient
        if (client == null) {
            if (!placesMissingNotified) {
                placesMissingNotified = true
                Toast.makeText(
                    requireContext(),
                    R.string.places_not_configured,
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val serial = ++addressSearchSerial

        fun executeSearch(bounds: RectangularBounds?) {
            val builder = FindAutocompletePredictionsRequest.builder()
                .setSessionToken(autocompleteSessionToken)
                .setQuery(query)
            bounds?.let { builder.setLocationBias(it) }
            val request = builder.build()

            client.findAutocompletePredictions(request)
                .addOnSuccessListener { response ->
                    if (serial != addressSearchSerial) return@addOnSuccessListener
                    if (etAddress.text?.toString()?.trim() != query) return@addOnSuccessListener
                    addressPopupData.clear()
                    addressPopupData.addAll(
                        response.autocompletePredictions.map { AutocompleteRow(it) }
                    )
                    addressPopupAdapter?.notifyDataSetChanged()
                    val popupWindow = addressListPopup ?: return@addOnSuccessListener
                    popupWindow.anchorView = tilAddress
                    val w = tilAddress.width
                    if (w > 0) popupWindow.setContentWidth(w) else {
                        tilAddress.post {
                            if (tilAddress.width > 0) {
                                addressListPopup?.setContentWidth(tilAddress.width)
                            }
                        }
                    }
                    if (addressPopupData.isEmpty()) {
                        popupWindow.dismiss()
                    } else if (etAddress.isFocused) {
                        popupWindow.show()
                    }
                }
                .addOnFailureListener {
                    if (serial != addressSearchSerial) return@addOnFailureListener
                    addressListPopup?.dismiss()
                }
        }

        if (hasLocationForCurrentPosition()) {
            LocationServices.getFusedLocationProviderClient(requireContext()).lastLocation
                .addOnSuccessListener { loc ->
                    val bounds = loc?.let {
                        val d = 0.12
                        RectangularBounds.newInstance(
                            LatLng(it.latitude - d, it.longitude - d),
                            LatLng(it.latitude + d, it.longitude + d)
                        )
                    }
                    executeSearch(bounds)
                }
                .addOnFailureListener { executeSearch(null) }
        } else {
            executeSearch(null)
        }
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
        resolved: ResolvedGeofenceAddress?,
        radiusMeters: Float,
        tvStatusValue: TextView,
        insideOutsideContainer: View,
        tvInsideOutside: TextView,
        tvInsideOutsideDot: View,
        btnRemove: MaterialButton,
        heroStatusDot: View,
        heroStatusLabel: TextView
    ) {
        if (resolved == null) {
            Toast.makeText(requireContext(), R.string.address_pick_suggestion_or_location, Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit().putString(AnchorPrefs.KEY_GEOFENCE_ADDRESS, address).apply()
        val source = if (resolved.fromGps) "gps" else "places"
        geofenceManager.addGeofence(
            lat = resolved.latitude,
            lng = resolved.longitude,
            radiusMeters = radiusMeters,
            onSuccess = {
                requireActivity().runOnUiThread {
                    TelemetryTracker.logEvent("focus_zone_set", mapOf("source" to source))
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
    }

    private data class AutocompleteRow(val prediction: AutocompletePrediction) {
        override fun toString(): String = prediction.getFullText(null).toString()
    }
}