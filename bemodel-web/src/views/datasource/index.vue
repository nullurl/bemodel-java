<template><ConnectorFactory v-if="industrial"/><JdbcWorkbench v-else/></template>
<script setup>
import ConnectorFactory from './ConnectorFactory.vue'
import JdbcWorkbench from './JdbcWorkbench.vue'
const industrial = import.meta.env.VITE_INDUSTRY === 'forgepilot'
</script>
