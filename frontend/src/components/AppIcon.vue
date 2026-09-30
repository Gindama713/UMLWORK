<script setup>
defineProps({ name: { type: String, default: 'parking' } })
const paths = {
  dashboard: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  car: 'M5 10l2-5h10l2 5 M3 10h18v8H3z M6 14h2 M16 14h2 M5 18v3 M19 18v3',
  ticket: 'M3 6h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4z M15 6v3 M15 11v2 M15 15v3',
  bolt: 'M13 2L4 14h7l-1 8 10-13h-7z',
  wallet: 'M20 7H4a2 2 0 0 1 0-4h14v4 M3 5v14a2 2 0 0 0 2 2h15V7 M20 11h-6v6h6 M16 14h.01',
  parking: 'M5 3h14v18H5z M10 16V7h3a3 3 0 0 1 0 6h-3',
  chart: 'M4 3v17h17 M8 15v-4 M13 15V7 M18 15V4',
  plane: 'M22 2L9 9l-6-2-2 2 6 4-2 6 2 2 4-6 4 6 2-2-2-6z',
  arrow: 'M4 12h16 M14 6l6 6-6 6',
  arrowUp: 'M6 18L18 6 M6 6h12v12',
  refresh: 'M20 8a8 8 0 1 0 1 7 M20 3v5h-5',
  search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  pin: 'M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0z M15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  clock: 'M12 8v5l3 2 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  check: 'M5 12l4 4L19 6',
  layers: 'M12 3l10 5-10 5L2 8z M2 12l10 5 10-5 M2 16l10 5 10-5',
  accessible: 'M12 3h.01 M9 7h6 M12 6v8h5l3 5 M9 11a5 5 0 1 0 6 8',
}
</script>
<template><svg class="app-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.parking" /></svg></template>
