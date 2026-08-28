<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref<string | null>(null)
const status = ref<'idle' | 'submitting' | 'success'>('idle')

// 按钮文案是「登录」，成功后的提示是「已登录」——同名规矩：
// 动作词与完成态共享同一个词根，用户不用去猜"已登录"和"登录成功"是不是两件事。
const buttonLabel = computed(() => {
  if (status.value === 'submitting') return '登录中…'
  if (status.value === 'success') return '已登录'
  return '登录'
})

async function submit(): Promise<void> {
  if (status.value !== 'idle') return
  error.value = null
  status.value = 'submitting'
  try {
    await auth.login(username.value, password.value)
    status.value = 'success'
    // 回到登录前被守卫拦下的那个地址；没有的话（比如直接访问 /login）就去视频首页。
    const redirect = route.query.redirect
    await router.push(typeof redirect === 'string' ? redirect : { name: 'video-home' })
  } catch (err) {
    status.value = 'idle'
    error.value = err instanceof Error ? err.message : '登录失败，请重试。'
  }
}
</script>

<template>
  <div class="login">
    <form class="card" @submit.prevent="submit">
      <h1 class="title">MyMedia</h1>
      <p class="subtitle">登录后继续</p>

      <label class="field">
        <span class="field-label">用户名</span>
        <input
          v-model="username"
          type="text"
          name="username"
          autocomplete="username"
          required
          autofocus
        />
      </label>

      <label class="field">
        <span class="field-label">密码</span>
        <input
          v-model="password"
          type="password"
          name="password"
          autocomplete="current-password"
          required
        />
      </label>

      <p v-if="error" class="error" role="alert">{{ error }}</p>

      <button type="submit" class="submit" :disabled="status !== 'idle'">
        {{ buttonLabel }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - 200px);
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  width: min(360px, 100%);
  padding: var(--space-6) var(--space-5);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.title {
  font-family: var(--display);
  font-size: var(--step-2);
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--text);
}

.subtitle {
  margin-top: calc(var(--space-2) * -1);
  font-size: var(--step-0);
  color: var(--dim);
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.field-label {
  font-size: var(--step--1);
  font-weight: 600;
  color: var(--dim);
}

.field input {
  padding: var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  transition: border-color var(--dur-fast) var(--ease);
}

.field input:focus-visible {
  border-color: var(--accent);
}

.error {
  font-size: var(--step--1);
  color: var(--text);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-left: 2px solid var(--accent);
  border-radius: var(--radius);
  background: var(--ground);
}

.submit {
  margin-top: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: none;
  border-radius: var(--radius);
  background: var(--accent);
  color: var(--shell-ground);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 700;
  cursor: pointer;
  transition: opacity var(--dur-fast) var(--ease);
}

.submit:hover:not(:disabled) {
  opacity: 0.9;
}

.submit:disabled {
  cursor: default;
  opacity: 0.6;
}
</style>
