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
        val insideOutsideContainer = view.findViewById<View>(R.id.insideOutsideContainer)
        val tvInsideOutside = view.findViewById<TextView>(R.id.tvInsideOutside)
        val tvInsideOutsideDot = view.findViewById<View>(R.id.tvInsideOutsideDot)
        val heroStatusDot = view.findViewById<View>(R.id.heroStatusDot)
        val heroStatusLabel = view.findViewById<TextView>(R.id.heroStatusLabel)

        sliderRadius.addOnChangeListener { _, value, _ ->
            tvRadiusLabel.text = formatRadiusValue(value.toInt())
        }
        tvRadiusLabel.text = formatRadiusValue(sliderRadius.value.toInt())

        btnSet.setOnClickListener {
            val address = etAddress.text?.toString()?.trim()
            if (address.isNullOrBlank()) {
                etAddress.error = getString(R.string.address_required)
                return@setOnClickListener
            }

            val radiusMeters = sliderRadius.value
            resolveAndSetGeofence(
                address,
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
        radiusMeters: Float,
        tvStatusValue: TextView,
        insideOutsideContainer: View,
        tvInsideOutside: TextView,
        tvInsideOutsideDot: View,
        btnRemove: MaterialButton,
        heroStatusDot: View,
        heroStatusLabel: TextView
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
