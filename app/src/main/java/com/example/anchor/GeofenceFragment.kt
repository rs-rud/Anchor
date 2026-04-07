package com.example.anchor

import android.content.Context
import android.content.SharedPreferences
import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import java.io.IOException
import java.util.Locale

class GeofenceFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private lateinit var geofenceManager: GeofenceManager

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_geofence, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(AnchorPrefs.FILE_NAME, Context.MODE_PRIVATE)
        geofenceManager = GeofenceManager(requireContext())

        val etAddress = view.findViewById<TextInputEditText>(R.id.etAddress)
        val sliderRadius = view.findViewById<Slider>(R.id.sliderRadius)
        val tvRadiusLabel = view.findViewById<TextView>(R.id.tvRadiusLabel)
        val btnSet = view.findViewById<MaterialButton>(R.id.btnSetGeofence)
        val btnRemove = view.findViewById<MaterialButton>(R.id.btnRemoveGeofence)
        val tvStatusValue = view.findViewById<TextView>(R.id.tvStatusValue)
        val tvInsideOutside = view.findViewById<TextView>(R.id.tvInsideOutside)

        sliderRadius.addOnChangeListener { _, value, _ ->
            tvRadiusLabel.text = getString(R.string.radius_label_format, value.toInt())
        }
        tvRadiusLabel.text = getString(R.string.radius_label_format, sliderRadius.value.toInt())

        btnSet.setOnClickListener {
            val address = etAddress.text?.toString()?.trim()
            if (address.isNullOrBlank()) {
                etAddress.error = getString(R.string.address_required)
                return@setOnClickListener
            }

            val radiusMeters = sliderRadius.value
            resolveAndSetGeofence(address, radiusMeters, tvStatusValue, tvInsideOutside, btnRemove)
        }

        btnRemove.setOnClickListener {
            geofenceManager.removeGeofence(
                onSuccess = {
                    requireActivity().runOnUiThread {
                        tvStatusValue.text = getString(R.string.no_geofence_set)
                        tvInsideOutside.visibility = View.GONE
                        btnRemove.visibility = View.GONE
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

        refreshStatus(tvStatusValue, tvInsideOutside, btnRemove)
    }

    override fun onResume() {
        super.onResume()
        val tvStatusValue = view?.findViewById<TextView>(R.id.tvStatusValue) ?: return
        val tvInsideOutside = view?.findViewById<TextView>(R.id.tvInsideOutside) ?: return
        val btnRemove = view?.findViewById<MaterialButton>(R.id.btnRemoveGeofence) ?: return
        refreshStatus(tvStatusValue, tvInsideOutside, btnRemove)
    }

    private fun refreshStatus(tvStatusValue: TextView, tvInsideOutside: TextView, btnRemove: MaterialButton) {
        val active = prefs.getBoolean(AnchorPrefs.KEY_GEOFENCE_ACTIVE, false)
        if (active) {
            val address = prefs.getString(AnchorPrefs.KEY_GEOFENCE_ADDRESS, "") ?: ""
            val radius = prefs.getFloat(AnchorPrefs.KEY_GEOFENCE_RADIUS, 0f).toInt()
            tvStatusValue.text = getString(R.string.geofence_active_format, address, radius)
            btnRemove.visibility = View.VISIBLE

            val inside = prefs.getBoolean(AnchorPrefs.KEY_IS_INSIDE_GEOFENCE, false)
            tvInsideOutside.visibility = View.VISIBLE
            tvInsideOutside.text = if (inside) {
                getString(R.string.inside_geofence)
            } else {
                getString(R.string.outside_geofence)
            }
        } else {
            tvStatusValue.text = getString(R.string.no_geofence_set)
            tvInsideOutside.visibility = View.GONE
            btnRemove.visibility = View.GONE
        }
    }

    private fun resolveAndSetGeofence(
        address: String,
        radiusMeters: Float,
        tvStatusValue: TextView,
        tvInsideOutside: TextView,
        btnRemove: MaterialButton
    ) {
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
                            refreshStatus(tvStatusValue, tvInsideOutside, btnRemove)
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
