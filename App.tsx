import React, { useState, useEffect, useRef } from 'react';
import {
  View, Text, StyleSheet, ScrollView, TouchableOpacity,
  Platform, PermissionsAndroid, SafeAreaView
} from 'react-native';
import { BleManager } from 'react-native-ble-plx';

const bleManager = new BleManager();

type Fingerprint = Record<string, number>;

const DISTANCE_THRESHOLD = 50;
const DEFAULT_WEAK_RSSI = -100;
const RSSI_IGNORE = -90;
const MIN_SHARED_ANCHORS = 2;

export default function App() {

  const [mode, setMode] = useState<'idle' | 'calibrating' | 'monitoring'>('idle');
  const [inRoom, setInRoom] = useState<boolean>(false);
  const [fingerprints, setFingerprints] = useState<Fingerprint[]>([]);
  const [liveDevices, setLiveDevices] = useState<Fingerprint>({});
  const [currentDistance, setCurrentDistance] = useState<number | null>(null);

  const fingerprintsRef = useRef<Fingerprint[]>([]);
  const currentScanBuffer = useRef<Fingerprint>({});
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    fingerprintsRef.current = fingerprints;
  }, [fingerprints]);

  // ---------------- PERMISSIONS ----------------

  const requestPermissions = async () => {

    if (Platform.OS === 'android') {

      const granted = await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
      ]);

      return (
        granted['android.permission.ACCESS_FINE_LOCATION'] === PermissionsAndroid.RESULTS.GRANTED &&
        granted['android.permission.BLUETOOTH_SCAN'] === PermissionsAndroid.RESULTS.GRANTED
      );
    }

    return true;
  };

  // ---------------- DISTANCE FUNCTION ----------------

  const calculateDistance = (current: Fingerprint, saved: Fingerprint): number => {

    const anchorKeys = Object.keys(saved);

    let sum = 0;
    let shared = 0;

    anchorKeys.forEach(mac => {

      const savedVal = saved[mac];

      const currentVal =
        current[mac] !== undefined ? current[mac] : DEFAULT_WEAK_RSSI;

      if (current[mac] !== undefined) shared++;

      const diff = currentVal - savedVal;

      sum += diff * diff;

    });

    if (shared < MIN_SHARED_ANCHORS) {
      return Infinity;
    }

    return Math.sqrt(sum);
  };

  // ---------------- BLE SCAN ----------------

  const startScanning = async (newMode: 'calibrating' | 'monitoring') => {

    const hasPermission = await requestPermissions();

    if (!hasPermission) {
      alert("Bluetooth permissions required.");
      return;
    }

    setMode(newMode);

    currentScanBuffer.current = {};
    setLiveDevices({});
    setCurrentDistance(null);

    bleManager.startDeviceScan(null, null, (error, device) => {

      if (error) {
        console.log("Scan error:", error);
        return;
      }

      if (!device?.id || device.rssi == null) return;

      if (device.rssi < RSSI_IGNORE) return;

      const key = device.localName || device.id;

      const existing = currentScanBuffer.current[key];

      if (!existing || device.rssi > existing) {
        currentScanBuffer.current[key] = device.rssi;
      }

    });

    intervalRef.current = setInterval(() => evaluateState(newMode), 4000);
  };

  const stopScanning = () => {

    bleManager.stopDeviceScan();

    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }

    setMode('idle');
  };

  // ---------------- EVALUATION ----------------

  const evaluateState = (currentMode: 'calibrating' | 'monitoring') => {

    const currentData = { ...currentScanBuffer.current };

    setLiveDevices(currentData);

    if (Object.keys(currentData).length === 0) return;

    if (currentMode === 'calibrating') {

      setFingerprints(prev => [...prev, currentData]);

    }

    else if (currentMode === 'monitoring') {

      const savedFingerprints = fingerprintsRef.current;

      if (savedFingerprints.length === 0) return;

      let minDistance = Infinity;

      savedFingerprints.forEach(saved => {

        const dist = calculateDistance(currentData, saved);

        if (dist < minDistance) {
          minDistance = dist;
        }

      });

      if (minDistance !== Infinity) {

        setCurrentDistance(Math.round(minDistance));

        setInRoom(minDistance <= DISTANCE_THRESHOLD);

      }
    }

    currentScanBuffer.current = {};
  };

  // ---------------- CLEANUP ----------------

  useEffect(() => {

    return () => {

      bleManager.destroy();

      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }

    };

  }, []);

  // ---------------- UI ----------------

  const sortedLiveDevices = Object.entries(liveDevices)
    .sort((a, b) => b[1] - a[1]);

  return (

    <SafeAreaView style={styles.safeArea}>

      <ScrollView contentContainerStyle={styles.container}>

        <Text style={styles.title}>Anchor Geofence</Text>

        <View style={styles.card}>

          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Mode:</Text>
            <Text style={styles.statusValue}>{mode}</Text>
          </View>

          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Location:</Text>
            <Text style={styles.statusValue}>
              {mode === 'monitoring'
                ? (inRoom ? "🟢 INSIDE ZONE" : "🔴 OUTSIDE ZONE")
                : "---"}
            </Text>
          </View>

          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Saved Points:</Text>
            <Text style={styles.statusValue}>{fingerprints.length}</Text>
          </View>

          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Live Distance Score:</Text>
            <Text style={styles.statusValue}>
              {currentDistance !== null
                ? `${currentDistance} / ${DISTANCE_THRESHOLD}`
                : "---"}
            </Text>
          </View>

        </View>

        {/* CALIBRATION */}

        <View style={styles.section}>

          <Text style={styles.sectionTitle}>Calibration</Text>

          <View style={styles.buttonRow}>

            <TouchableOpacity
              style={[styles.button, styles.btnPrimary, mode !== 'idle' && styles.btnDisabled]}
              onPress={() => startScanning('calibrating')}
              disabled={mode !== 'idle'}
            >
              <Text style={styles.btnText}>Start Walk</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.button, styles.btnDanger, mode !== 'calibrating' && styles.btnDisabled]}
              onPress={stopScanning}
              disabled={mode !== 'calibrating'}
            >
              <Text style={styles.btnText}>Stop</Text>
            </TouchableOpacity>

          </View>

        </View>

        {/* MONITOR */}

        <View style={styles.section}>

          <Text style={styles.sectionTitle}>Monitoring</Text>

          <View style={styles.buttonRow}>

            <TouchableOpacity
              style={[styles.button, styles.btnSuccess, (mode !== 'idle' || fingerprints.length === 0) && styles.btnDisabled]}
              onPress={() => startScanning('monitoring')}
              disabled={mode !== 'idle' || fingerprints.length === 0}
            >
              <Text style={styles.btnText}>Start Monitor</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.button, styles.btnDanger, mode !== 'monitoring' && styles.btnDisabled]}
              onPress={stopScanning}
              disabled={mode !== 'monitoring'}
            >
              <Text style={styles.btnText}>Stop</Text>
            </TouchableOpacity>

          </View>

        </View>

        <TouchableOpacity
          style={[styles.button, styles.btnSecondary, mode !== 'idle' && styles.btnDisabled]}
          onPress={() => setFingerprints([])}
          disabled={mode !== 'idle'}
        >
          <Text style={styles.btnTextDark}>Clear Dataset</Text>
        </TouchableOpacity>

        {/* LIVE BLE */}

        <View style={[styles.card, styles.liveViewCard]}>

          <Text style={styles.sectionTitle}>Live BLE Environment</Text>

          <Text style={styles.subtitle}>
            Devices: {sortedLiveDevices.length}
          </Text>

          {sortedLiveDevices.map(([mac, rssi]) => (

            <View key={mac} style={styles.deviceRow}>

              <Text style={styles.macText}>{mac}</Text>

              <Text style={styles.rssiText}>{rssi} dBm</Text>

            </View>

          ))}

        </View>

      </ScrollView>

    </SafeAreaView>
  );
}

const styles = StyleSheet.create({

  safeArea: { flex: 1, backgroundColor: '#F2F2F7' },

  container: { flexGrow: 1, padding: 20, alignItems: 'center' },

  title: { fontSize: 28, fontWeight: '800', marginBottom: 20 },

  card: {
    backgroundColor: '#fff',
    borderRadius: 16,
    padding: 20,
    width: '100%',
    marginBottom: 20
  },

  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginVertical: 6
  },

  statusLabel: { fontSize: 16, color: '#666' },

  statusValue: { fontSize: 16, fontWeight: '700' },

  section: { width: '100%', marginBottom: 20 },

  sectionTitle: { fontSize: 18, fontWeight: '700', marginBottom: 10 },

  buttonRow: { flexDirection: 'row', gap: 10 },

  button: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center'
  },

  btnPrimary: { backgroundColor: '#007AFF' },

  btnSuccess: { backgroundColor: '#34C759' },

  btnDanger: { backgroundColor: '#FF3B30' },

  btnSecondary: { backgroundColor: '#E5E5EA' },

  btnDisabled: { opacity: 0.4 },

  btnText: { color: '#fff', fontWeight: '600' },

  btnTextDark: { color: '#111', fontWeight: '600' },

  subtitle: { marginBottom: 10, color: '#888' },

  deviceRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6
  },

  macText: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace'
  },

  rssiText: { fontWeight: '600' },

  liveViewCard: { width: '100%' }

});