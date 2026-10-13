// Copy to layout.js and fill in your own Home Assistant entity ids (Settings -> Entities).
// layout.js is deployed next to control.html and is not committed.
//
// kind: toggle (light/switch) · climate (target temperature -/+) · cover (garage/door, asks first)
//       lock (asks first) · scene (asks first, uses `confirm`) · weather · person
window.KF_LAYOUT = {
  columns: [
    { title: "Climate", items: [
      { id: "weather.home", name: "Weather", kind: "weather" },
      { id: "climate.living_room", name: "Living Room", kind: "climate" }
    ]},
    { title: "Lights", items: [
      { id: "light.kitchen", name: "Kitchen", kind: "toggle" },
      { id: "light.dining_room", name: "Dining Room", kind: "toggle" },
      { id: "scene.all_lights_off", name: "All Lights Off", kind: "scene", confirm: "Turn off every light?" }
    ]},
    { title: "Doors & People", items: [
      { id: "cover.garage_door", name: "Garage", kind: "cover" },
      { id: "lock.front_door", name: "Front Door", kind: "lock" },
      { id: "person.someone", name: "Someone", kind: "person" }
    ]}
  ],
  // Night mode (dim clock). helper: a binary_sensor ON when the frame should sleep (see README).
  // light + sun: fallback used until the helper exists. Leave all empty to disable night mode.
  night: { helper: "binary_sensor.kitchen_frame_night", light: "light.kitchen", sun: "sun.sun" }
};
