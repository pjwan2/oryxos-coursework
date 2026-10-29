<script setup>
const links = [
  { to: '/sessions', label: 'Sessions' },
  { to: '/profiles', label: 'Profiles' },
  { to: '/tools', label: 'Tools' },
  { to: '/memory', label: 'Memory' },
  { to: '/system', label: 'System' },
  { to: '/scheduler', label: 'Scheduler' }
]
</script>

<template>
  <div class="shell">
    <aside class="nav">
      <div class="brand">Oryx<span>OS</span></div>
      <router-link v-for="link in links" :key="link.to" :to="link.to" class="nav-link">
        {{ link.label }}
      </router-link>
      <p class="nav-note muted">Trusted local use only. No authentication.</p>
    </aside>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  min-height: 100vh;
}
.nav {
  border-right: 1px solid var(--border);
  padding: 20px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.brand {
  font-weight: 700;
  font-size: 18px;
  margin: 0 8px 16px;
}
.brand span {
  color: var(--accent);
}
.nav-link {
  color: var(--muted);
  padding: 6px 10px;
  border-radius: 6px;
  text-decoration: none;
}
.nav-link:hover {
  color: var(--text);
  text-decoration: none;
}
.nav-link.router-link-active {
  color: var(--text);
  background: var(--card);
  border-left: 2px solid var(--accent);
}
.nav-note {
  margin: auto 8px 0;
  font-size: 12px;
}
.content {
  padding: 24px 32px 48px;
  min-width: 0;
}
@media (max-width: 760px) {
  .shell {
    grid-template-columns: 1fr;
  }
  .nav {
    flex-direction: row;
    flex-wrap: wrap;
    border-right: none;
    border-bottom: 1px solid var(--border);
  }
  .nav-note {
    display: none;
  }
}
</style>
