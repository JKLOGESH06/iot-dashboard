// app.js - Logic for Real-Time IoT Dashboard

// DOM Elements
const currentValueEl = document.getElementById('currentValue');
const systemStateEl = document.getElementById('systemState');
const statusDescEl = document.getElementById('statusDesc');
const timestampEl = document.getElementById('timestamp');
const alertFeedEl = document.getElementById('alertFeed');
const btnEnableNotifications = document.getElementById('btnEnableNotifications');
const connBadge = document.getElementById('connBadge');
const connText = document.getElementById('connText');

const powerValueEl = document.getElementById('powerValue');
const energyValueEl = document.getElementById('energyValue');
const simIdDisplayEl = document.getElementById('simIdDisplay');

const loginOverlay = document.getElementById('loginOverlay');
const simInput = document.getElementById('simInput');
const btnConnectDevice = document.getElementById('btnConnectDevice');
const loginError = document.getElementById('loginError');

// State tracking
let isOverloaded = false;
let isConnected = false;
let mockStreamInterval = null;
let totalEnergyKwh = 0.0;
const VOLTAGE = 230; // Assuming 230V AC for power calculation
let ACTIVE_SIM_NUMBER = "";

// Request Browser Notifications
btnEnableNotifications.addEventListener('click', () => {
    if ("Notification" in window) {
        Notification.requestPermission().then(permission => {
            if (permission === 'granted') {
                btnEnableNotifications.textContent = 'Push Alerts Enabled \u2713';
                btnEnableNotifications.style.borderColor = 'var(--success-color)';
                btnEnableNotifications.style.color = 'var(--success-color)';
                
                new Notification("Notifications Enabled", {
                    body: "You will receive an alert if an overload triggers.",
                    icon: "https://cdn-icons-png.flaticon.com/512/3598/3598385.png"
                });
            }
        });
    } else {
        alert("This browser does not support desktop notifications.");
    }
});

function fireSystemNotification(title, message) {
    if ("Notification" in window && Notification.permission === "granted") {
        new Notification(title, { body: message });
    }
}

// Function to update UI safely
function updateDashboard(data) {
    const { current, alertStatus, time, simId } = data;

    if (simId) {
        simIdDisplayEl.textContent = simId;
    }

    // Calculate Power (Watts) = Current * Voltage
    const powerW = current * VOLTAGE;
    
    // Accumulate Energy (kWh). Assuming update happens every ~2 seconds.
    // 2 seconds = 2/3600 hours. kW = Watts / 1000
    const timeDeltaHours = 2.0 / 3600.0;
    totalEnergyKwh += (powerW / 1000.0) * timeDeltaHours;

    // Update Readings
    currentValueEl.textContent = parseFloat(current).toFixed(3);
    powerValueEl.textContent = powerW.toFixed(1);
    energyValueEl.textContent = totalEnergyKwh.toFixed(4);
    
    timestampEl.textContent = time;

    // Check for Overload
    if (alertStatus === "Overload detected!" && !isOverloaded) {
        isOverloaded = true;
        
        // CSS State changes
        systemStateEl.className = 'state-badge overload';
        systemStateEl.textContent = 'OVERLOAD';
        statusDescEl.textContent = 'Critical threshold exceeded! Load has been cut off.';
        currentValueEl.classList.add('danger');
        powerValueEl.classList.add('danger');
        
        // Add to Feed
        addToFeed('Overload Cut-off triggered!', time, 'danger');
        
        // Trigger Push Alert
        fireSystemNotification('Electrical Overload!', `Load cut off. Current spiked to ${current}A!`);
        
    } else if (alertStatus === "Normal" && isOverloaded) {
        isOverloaded = false;
        
        // CSS State changes
        systemStateEl.className = 'state-badge normal';
        systemStateEl.textContent = 'NORMAL';
        statusDescEl.textContent = 'The load is comfortably within operational parameters.';
        currentValueEl.classList.remove('danger');
        powerValueEl.classList.remove('danger');
        
        addToFeed('System stabilized. Load restored.', time, 'normal');
    }
}

function addToFeed(msg, time, type) {
    // remove placeholder if exists
    const placeholder = document.querySelector('.placeholder');
    if (placeholder) placeholder.remove();

    const div = document.createElement('div');
    div.className = `feed-item ${type}`;
    div.innerHTML = `
        <span class="feed-msg">${msg}</span>
        <span class="feed-time">${time}</span>
    `;
    alertFeedEl.prepend(div);
}

// ----------------------------------------------------------------------------------
// FIREBASE CONNECTION SETUP
// ----------------------------------------------------------------------------------

// TODO: Replace these placeholders with your actual Firebase Project Configuration!
// You get this from Firebase Console -> Project Settings -> General -> Your Apps -> Config
const firebaseConfig = {
    apiKey: "AIzaSyBXvfgmx0FX-xkE2FqCaFxqZ6fsx47Af4s",
    authDomain: "loadmonitor-ccc0e.firebaseapp.com",
    databaseURL: "https://loadmonitor-ccc0e-default-rtdb.firebaseio.com",
    projectId: "loadmonitor-ccc0e",
    storageBucket: "loadmonitor-ccc0e.firebasestorage.app",
    messagingSenderId: "74316918641",
    appId: "1:74316918641:web:4cc41ab39cd65d81cd10d0"
};

// Initialize Firebase (Using V8 Namespaced API for local HTML compatibility)
if (!firebase.apps.length) {
    firebase.initializeApp(firebaseConfig);
}
const db = firebase.database();
let dbListenerRef = null;

function startDeviceStream(simNumber) {
    console.log(`Connecting to Firebase node: devices/${simNumber}`);
    
    // Stop any existing listens
    stopDeviceStream();
    
    dbListenerRef = db.ref('devices/' + simNumber);
    dbListenerRef.on('value', (snapshot) => {
        const data = snapshot.val();
        if (data) {
            // Overload safety parsing
            const currentAmp = data.current || 0;
            const status = data.alertStatus || "Normal";
            const updatedTime = data.time || new Date().toLocaleTimeString();
            
            updateDashboard({ 
                current: currentAmp, 
                alertStatus: status, 
                time: updatedTime, 
                simId: simNumber 
            });
        }
    });
}

function stopDeviceStream() {
    if (dbListenerRef) {
        dbListenerRef.off();
        dbListenerRef = null;
    }
}



// Connection Badge Click Handler (Toggle Connect/Disconnect)
connBadge.style.cursor = "pointer";
connBadge.addEventListener('click', () => {
    if (isConnected) {
        isConnected = false;
        connBadge.classList.remove('connected');
        connBadge.classList.add('disconnected');
        connText.textContent = "Disconnected";
        stopDeviceStream();
        addToFeed("System disconnected from DB.", new Date().toLocaleTimeString(), "normal");
    } else {
        isConnected = true;
        connBadge.classList.remove('disconnected');
        connBadge.classList.add('connected');
        connText.textContent = "Connected to DB";
        startDeviceStream(ACTIVE_SIM_NUMBER);
        addToFeed("System connected to DB.", new Date().toLocaleTimeString(), "normal");
    }
});

// Login / Connect Device Handler
btnConnectDevice.addEventListener('click', () => {
    const simValue = simInput.value.trim();
    if (!simValue) {
        loginError.textContent = "Please enter a valid SIM Number.";
        return;
    }
    
    // Simulate Authentication & Connection Delay
    btnConnectDevice.textContent = "Connecting...";
    setTimeout(() => {
        ACTIVE_SIM_NUMBER = simValue;
        loginOverlay.classList.add('hidden'); // Hide the login modal
        
        // Ensure UI says connected
        isConnected = true;
        connBadge.classList.remove('disconnected');
        connBadge.classList.add('connected');
        connText.textContent = "Connected to DB";
        
        startDeviceStream(ACTIVE_SIM_NUMBER); // Begin Live Data feed from Firebase!
        addToFeed("System connected to DB.", new Date().toLocaleTimeString(), "normal");
    }, 1000);
});

// Initially set badge disconnected
connBadge.classList.add('disconnected');
connBadge.classList.remove('connected');
connText.textContent = "Disconnected";

// Update SMS Destination Handler
const btnUpdateSms = document.getElementById('btnUpdateSms');
const smsAlertInput = document.getElementById('smsAlertInput');
btnUpdateSms.addEventListener('click', () => {
    const targetNumber = smsAlertInput.value.trim();
    if (targetNumber && isConnected) {
        btnUpdateSms.textContent = "\u2713"; // Checkmark
        btnUpdateSms.style.background = "var(--success-color)";
        setTimeout(() => {
            btnUpdateSms.textContent = "Set";
            btnUpdateSms.style.background = "";
        }, 2000);
        
        // Push the new SMS alert destination into Firebase!
        db.ref('devices/' + ACTIVE_SIM_NUMBER + '/smsTarget').set(targetNumber)
            .then(() => {
                addToFeed(`Emergency SMS target uploaded to: ${targetNumber}`, new Date().toLocaleTimeString(), "normal");
            })
            .catch((error) => {
                addToFeed(`DB Error: Failed to upload SMS target.`, new Date().toLocaleTimeString(), "danger");
                console.error(error);
            });
    } else if (!isConnected) {
        alert("Please connect to a hardware module first!");
    }
});
