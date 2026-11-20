import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

function loadJsonData<T>(filename: string, fallback: T): T {
  try {
    const filePath = path.resolve(process.cwd(), 'server', 'data', filename)
    const raw = fs.readFileSync(filePath, 'utf-8')
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

export const doctorDnsServices = loadJsonData<Record<string, unknown>>('doctor-dns-services.json', { services: [] })
export const doctorDnsSource = loadJsonData<Record<string, unknown>>('doctor-dns-source.json', {})
export const forkServicesData = loadJsonData<Record<string, unknown>>('fork-services.json', {})

const nowIso = () => new Date().toISOString()
const hoursAgoIso = (h: number) => new Date(Date.now() - h * 3600_000).toISOString()
const daysFromNowIso = (d: number) => new Date(Date.now() + d * 86400_000).toISOString()

export interface StoredRole {
  id: number
  name: string
  is_owner: boolean
  permissions: Record<string, Record<string, boolean | { scope: number }>>
  limits: Record<string, number | null>
  features: {
    can_use_reset_strategy: boolean
    can_use_next_plan: boolean
  }
  access: {
    require_template: boolean
    allowed_template_ids: number[] | null
    allowed_group_ids: number[] | null
  }
  hwid: {
    enabled: boolean
    forced: boolean
    require_hwid_for_manual_sub: boolean
    fallback_limit: number | null
    min_limit: number | null
    max_limit: number | null
    mode: 'disabled' | 'use_global' | 'override'
  }
  disabled_when_limited: boolean
  disconnect_users_when_limited: boolean
  disconnect_users_when_disabled: boolean
  created_at: string
}

export interface StoredAdmin {
  id: number
  username: string
  password: string
  telegram_id: number | null
  discord_webhook: string | null
  sub_domain: string | null
  profile_title: string | null
  support_url: string | null
  custom_variables: Array<{ key: string; value: string }>
  notification_enable: Record<string, boolean>
  used_traffic: number
  data_limit: number | null
  status: 'active' | 'disabled' | 'limited'
  sub_template: string | null
  lifetime_used_traffic: number
  note: string | null
  role_id: number
  permission_overrides: Record<string, number | null> | null
  is_disabled: boolean
  is_limited: boolean
}

export interface StoredCore {
  id: number
  name: string
  type: 'xray' | 'wg'
  config: Record<string, unknown>
  exclude_inbound_tags: string[]
  fallbacks_inbound_tags: string[]
  sort_order: number
  created_at: string
}

export interface StoredNode {
  id: number
  name: string
  address: string
  port: number
  api_port: number
  tunnel_api_port: number
  health_echo_port: number
  usage_coefficient: number
  connection_type: 'grpc' | 'rest'
  server_ca: string
  keep_alive: number
  core_config_id: number | null
  api_key: string | null
  data_limit: number
  data_limit_reset_strategy: 'no_reset' | 'day' | 'week' | 'month' | 'year'
  reset_time: number
  default_timeout: number
  internal_timeout: number
  proxy_url: string | null
  sort_order: number
  xray_version: string | null
  node_version: string | null
  status: 'connected' | 'connecting' | 'error' | 'disabled'
  message: string | null
  uplink: number
  downlink: number
  lifetime_uplink: number
  lifetime_downlink: number
  core_version: string | null
  gather_logs?: boolean
}

export interface StoredGroup {
  id: number
  name: string
  inbound_tags: string[]
  is_disabled: boolean
}

export interface StoredHost {
  id: number
  native_tunnel_id?: number | null
  remark: string
  address: string[]
  inbound_tag: string | null
  port: number | null
  sni: string[] | null
  host: string[] | null
  path: string | null
  security: 'inbound_default' | 'none' | 'tls'
  alpn: string[] | null
  fingerprint: string
  allowinsecure: boolean | null
  is_disabled: boolean
  http_headers: Record<string, string> | null
  transport_settings: Record<string, unknown> | null
  mux_settings: Record<string, unknown> | null
  fragment_settings: Record<string, unknown> | null
  noise_settings: Record<string, unknown> | null
  random_user_agent: boolean
  use_sni_as_host: boolean
  vless_route: string | null
  priority: number
  status: string[] | null
  ech: Record<string, unknown> | null
  pinned_peer_cert_sha256: string | null
  verify_peer_cert_by_name: string[] | null
  wireguard_overrides: Record<string, unknown> | null
  subscription_templates: Record<string, unknown> | null
  final_mask_settings: Record<string, unknown> | null
  cipher_suites: string | null
}

export interface StoredUser {
  id: number
  username: string
  status: 'active' | 'disabled' | 'limited' | 'expired' | 'on_hold'
  used_traffic: number
  lifetime_used_traffic: number
  data_limit: number | null
  data_limit_reset_strategy: 'no_reset' | 'day' | 'week' | 'month' | 'year'
  expire: string | number | null
  on_hold_expire_duration: number | null
  on_hold_timeout: string | number | null
  group_ids: number[]
  auto_delete_in_days: number | null
  hwid_limit: number | null
  note: string | null
  next_plan: Record<string, unknown> | null
  proxy_settings: Record<string, unknown>
  created_at: string
  edit_at: string | null
  online_at: string | null
  sub_updated_at: string | null
  sub_last_user_agent: string | null
  subscription_token: string
  subscription_url: string
  admin_id: number
}

export interface StoredUserTemplate {
  id: number
  name: string
  data_limit: number | null
  hwid_limit: number | null
  expire_duration: number | null
  username_prefix: string | null
  username_suffix: string | null
  group_ids: number[]
  extra_settings: Record<string, unknown> | null
  status: 'active' | 'on_hold' | null
  reset_usages: boolean
  on_hold_timeout: number | null
  data_limit_reset_strategy: 'no_reset' | 'day' | 'week' | 'month' | 'year'
  is_disabled: boolean
}

export interface StoredClientTemplate {
  id: number
  name: string
  template_type: 'clash' | 'clash-meta' | 'sing-box' | 'xray' | 'wireguard'
  content: string
  is_default: boolean
  is_system: boolean
}

export interface StoredApiKey {
  id: number
  name: string
  note: string | null
  prefix: string
  raw_key: string
  permissions: Record<string, Record<string, boolean | { scope: number }>>
  inherit_permissions: boolean
  expire_date: string | null
  max_requests: number | null
  requests_used: number
  delete_on_limit: boolean
  admin_id: number
  is_expired: boolean
  is_usable: boolean
  created_at: string
  last_used_at: string | null
}

export interface StoredTunnel {
  id: number
  name: string
  source_node_id: number
  exit_node_id: number
  inbound_tags: string[]
  domains: string[]
  port: number
  enabled: boolean
  health_enabled: boolean
  transport: 'shadowsocks-2022' | 'paqet' | 'backhaul' | 'gost' | 'frp'
  paqet_options: Record<string, unknown>
  carrier_options: Record<string, unknown>
  engine_id: string
  revision: number
  applied_revision: number
  networks: string[]
}

export interface StoredTunnelAudit {
  id: number
  created_at: string
  actor: string
  action: string
  target_id: string
  details: Record<string, unknown>
}

export interface StoredDNSPolicy {
  id: number
  name: string
  revision: number
  rules: Array<{
    domain: string
    action: string
    target?: string | null
    services?: string[]
  }>
}

export interface StoredDNSAccount {
  id: number
  user_id: number
  username: string
  policy_id: number
  policy_name: string
  enabled: boolean
  eligible: boolean
  query_limit: number
  qps: number
  period: number
  queries: number
  request_bytes: number
  response_bytes: number
  errors: number
  blocked: number
  relay_rps: number
  relay_enabled: boolean
  relay_byte_limit: number
  relay_bytes: number
  relay_reserved: number
  relay_uncertain_bytes: number
  token: string
  created_at: string
}

export interface StoredDNSLease {
  id: number
  account_id: number
  ip: string
  label: string | null
  created_at: string
  expires_at: string | null
}

export interface StoredSmartDNSRoute {
  id: number
  node_id: number
  policy_id: number
  domain: string
  public_address: string
  bind_address: string
  tcp_ports: number[]
  udp_ports: number[]
  mode: 'sniff' | 'fixed'
  enabled: boolean
  revision: number
  egress_tunnel_id: number | null
}

const DEFAULT_XRAY_CONFIG = {
  log: { loglevel: 'warning' },
  inbounds: [
    {
      tag: 'VLESS_REALITY',
      listen: '0.0.0.0',
      port: 443,
      protocol: 'vless',
      settings: { clients: [], decryption: 'none' },
      streamSettings: {
        network: 'tcp',
        security: 'reality',
        realitySettings: {
          show: false,
          dest: 'www.cloudflare.com:443',
          xver: 0,
          serverNames: ['www.cloudflare.com'],
          privateKey: 'cGxhY2Vob2xkZXJfcHJpdmF0ZV9rZXlfMzJfYnl0ZXM',
          shortIds: ['a1b2c3d4'],
        },
      },
    },
    {
      tag: 'TROJAN_WS',
      listen: '0.0.0.0',
      port: 8443,
      protocol: 'trojan',
      settings: { clients: [] },
      streamSettings: {
        network: 'ws',
        security: 'tls',
        wsSettings: { path: '/ws-trojan' },
      },
    },
    {
      tag: 'SS_2022',
      listen: '0.0.0.0',
      port: 9443,
      protocol: 'shadowsocks',
      settings: {
        method: '2022-blake3-aes-256-gcm',
        password: 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
        clients: [],
      },
    },
    {
      tag: 'WG_XRAY',
      listen: '0.0.0.0',
      port: 51820,
      protocol: 'wireguard',
      settings: {
        secretKey: 'cGxhY2Vob2xkZXJfd2dfc2VjcmV0X2tleV8zMl9ieXRlcw==',
        peers: [],
      },
    },
  ],
  outbounds: [
    { tag: 'direct', protocol: 'freedom', settings: {} },
    { tag: 'blocked', protocol: 'blackhole', settings: {} },
  ],
  routing: {
    domainStrategy: 'AsIs',
    rules: [{ type: 'field', outboundTag: 'blocked', ip: ['geoip:private'] }],
  },
}

const DEFAULT_WG_CONFIG = {
  interface: {
    tag: 'wg0',
    listenPort: 51820,
    address: ['10.10.0.1/24'],
    privateKey: 'cGxhY2Vob2xkZXJfd2dfY29yZV9wcml2YXRlX2tleQ==',
    mtu: 1280,
    dns: ['1.1.1.1'],
  },
  peers: [],
}

const fullCrud = () => ({ create: true, read: true, read_simple: true, update: true, delete: true })

export const store = {
  startedAt: Date.now(),
  tokens: new Map<string, number>([['demo-owner-token', 1]]),
  passkeys: new Map<number, Array<{ id: number; name: string; credential_id: string; created_at: string; last_used_at: string | null }>>(),

  roles: [
    {
      id: 1,
      name: 'Owner',
      is_owner: true,
      permissions: {
        dns: { ...fullCrud(), reset_usage: true, rotate_token: true },
        users: { ...fullCrud(), reset_usage: true, revoke_sub: true, set_owner: true, activate_next_plan: true },
        admins: { ...fullCrud(), reset_usage: true, passkeys: true },
        nodes: { ...fullCrud(), stats: true, logs: true, reconnect: true, reset_usage: true, sync: true },
        groups: fullCrud(),
        hosts: fullCrud(),
        templates: fullCrud(),
        client_templates: fullCrud(),
        cores: fullCrud(),
        settings: { read: true, read_general: true, update: true },
        system: { read: true },
        hwids: { read: true, delete: true, reset: true },
        admin_roles: fullCrud(),
        api_keys: { ...fullCrud(), revoke: true },
      },
      limits: {},
      features: { can_use_reset_strategy: true, can_use_next_plan: true },
      access: { require_template: false, allowed_template_ids: null, allowed_group_ids: null },
      hwid: { enabled: false, forced: false, require_hwid_for_manual_sub: false, fallback_limit: null, min_limit: null, max_limit: null, mode: 'use_global' },
      disabled_when_limited: false,
      disconnect_users_when_limited: false,
      disconnect_users_when_disabled: false,
      created_at: hoursAgoIso(720),
    },
    {
      id: 2,
      name: 'Reseller',
      is_owner: false,
      permissions: {
        users: {
          create: true,
          read: { scope: 1 },
          read_simple: { scope: 1 },
          update: { scope: 1 },
          delete: { scope: 1 },
          reset_usage: { scope: 1 },
          revoke_sub: { scope: 1 },
        },
        groups: { read: true, read_simple: true },
        templates: { read: true, read_simple: true },
        system: { read: true },
      },
      limits: { max_users: 250, data_limit_max: 500 * 1024 ** 3 },
      features: { can_use_reset_strategy: true, can_use_next_plan: true },
      access: { require_template: false, allowed_template_ids: null, allowed_group_ids: null },
      hwid: { enabled: false, forced: false, require_hwid_for_manual_sub: false, fallback_limit: 3, min_limit: 1, max_limit: 10, mode: 'use_global' },
      disabled_when_limited: true,
      disconnect_users_when_limited: true,
      disconnect_users_when_disabled: true,
      created_at: hoursAgoIso(500),
    },
  ] as StoredRole[],

  admins: [
    {
      id: 1,
      username: 'admin',
      password: 'admin',
      telegram_id: 100200300,
      discord_webhook: null,
      sub_domain: null,
      profile_title: 'PasarGuard Plus',
      support_url: 'https://t.me/Pasar_Guard',
      custom_variables: [],
      notification_enable: { create: true, modify: true, delete: true, status_change: true },
      used_traffic: 184 * 1024 ** 3,
      data_limit: null,
      status: 'active',
      sub_template: null,
      lifetime_used_traffic: 642 * 1024 ** 3,
      note: 'Primary panel owner',
      role_id: 1,
      permission_overrides: null,
      is_disabled: false,
      is_limited: false,
    },
    {
      id: 2,
      username: 'reseller_eu',
      password: 'reseller_eu',
      telegram_id: 400500600,
      discord_webhook: null,
      sub_domain: null,
      profile_title: 'EU Reseller',
      support_url: null,
      custom_variables: [],
      notification_enable: { create: true, modify: false, delete: false, status_change: true },
      used_traffic: 64 * 1024 ** 3,
      data_limit: 1024 * 1024 ** 3,
      status: 'active',
      sub_template: null,
      lifetime_used_traffic: 210 * 1024 ** 3,
      note: 'European distribution partner',
      role_id: 2,
      permission_overrides: null,
      is_disabled: false,
      is_limited: false,
    },
  ] as StoredAdmin[],

  cores: [
    {
      id: 1,
      name: 'Main Xray Core',
      type: 'xray',
      config: DEFAULT_XRAY_CONFIG,
      exclude_inbound_tags: [],
      fallbacks_inbound_tags: [],
      sort_order: 0,
      created_at: hoursAgoIso(720),
    },
    {
      id: 2,
      name: 'WireGuard Native',
      type: 'wg',
      config: DEFAULT_WG_CONFIG,
      exclude_inbound_tags: [],
      fallbacks_inbound_tags: [],
      sort_order: 1,
      created_at: hoursAgoIso(400),
    },
  ] as StoredCore[],

  nodes: [
    {
      id: 1,
      name: 'Frankfurt Edge',
      address: '185.199.108.153',
      port: 62050,
      api_port: 62051,
      tunnel_api_port: 62052,
      health_echo_port: 62053,
      usage_coefficient: 1.0,
      connection_type: 'grpc',
      server_ca: '-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n-----END CERTIFICATE-----',
      keep_alive: 60,
      core_config_id: 1,
      api_key: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
      data_limit: 5 * 1024 ** 4,
      data_limit_reset_strategy: 'month',
      reset_time: 1,
      default_timeout: 10,
      internal_timeout: 15,
      proxy_url: null,
      sort_order: 0,
      xray_version: '25.8.3',
      node_version: '1.2.4',
      status: 'connected',
      message: 'Node connected and synchronized',
      uplink: 142 * 1024 ** 3,
      downlink: 418 * 1024 ** 3,
      lifetime_uplink: 490 * 1024 ** 3,
      lifetime_downlink: 1320 * 1024 ** 3,
      core_version: '25.8.3',
    },
    {
      id: 2,
      name: 'Istanbul Relay',
      address: '91.108.56.10',
      port: 62050,
      api_port: 62051,
      tunnel_api_port: 62052,
      health_echo_port: 62053,
      usage_coefficient: 1.0,
      connection_type: 'grpc',
      server_ca: '-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n-----END CERTIFICATE-----',
      keep_alive: 60,
      core_config_id: 1,
      api_key: '6ba7b810-9dad-11d1-80b4-00c04fd430c8',
      data_limit: 3 * 1024 ** 4,
      data_limit_reset_strategy: 'month',
      reset_time: 1,
      default_timeout: 10,
      internal_timeout: 15,
      proxy_url: null,
      sort_order: 1,
      xray_version: '25.8.3',
      node_version: '1.2.4',
      status: 'connected',
      message: 'Node connected and synchronized',
      uplink: 88 * 1024 ** 3,
      downlink: 274 * 1024 ** 3,
      lifetime_uplink: 290 * 1024 ** 3,
      lifetime_downlink: 810 * 1024 ** 3,
      core_version: '25.8.3',
    },
    {
      id: 3,
      name: 'Helsinki WireGuard',
      address: '65.109.12.40',
      port: 62050,
      api_port: 62051,
      tunnel_api_port: 62052,
      health_echo_port: 62053,
      usage_coefficient: 1.0,
      connection_type: 'grpc',
      server_ca: '-----BEGIN CERTIFICATE-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n-----END CERTIFICATE-----',
      keep_alive: 60,
      core_config_id: 2,
      api_key: '9c5b94b1-35ad-49bb-b118-8e8fc24abf80',
      data_limit: 0,
      data_limit_reset_strategy: 'no_reset',
      reset_time: -1,
      default_timeout: 10,
      internal_timeout: 15,
      proxy_url: null,
      sort_order: 2,
      xray_version: null,
      node_version: '1.2.4',
      status: 'connected',
      message: 'WireGuard core active',
      uplink: 34 * 1024 ** 3,
      downlink: 92 * 1024 ** 3,
      lifetime_uplink: 120 * 1024 ** 3,
      lifetime_downlink: 310 * 1024 ** 3,
      core_version: '1.0.20210914',
    },
  ] as StoredNode[],

  groups: [
    { id: 1, name: 'Standard VIP', inbound_tags: ['VLESS_REALITY', 'TROJAN_WS'], is_disabled: false },
    { id: 2, name: 'High Speed Gaming', inbound_tags: ['SS_2022', 'WG_XRAY'], is_disabled: false },
  ] as StoredGroup[],

  hosts: [
    {
      id: 1,
      native_tunnel_id: null,
      remark: 'DE Reality Primary',
      address: ['de-edge.example.net'],
      inbound_tag: 'VLESS_REALITY',
      port: 443,
      sni: ['www.cloudflare.com'],
      host: null,
      path: null,
      security: 'inbound_default',
      alpn: ['h2', 'http/1.1'],
      fingerprint: 'chrome',
      allowinsecure: false,
      is_disabled: false,
      http_headers: null,
      transport_settings: null,
      mux_settings: null,
      fragment_settings: null,
      noise_settings: null,
      random_user_agent: false,
      use_sni_as_host: false,
      vless_route: null,
      priority: 1,
      status: null,
      ech: null,
      pinned_peer_cert_sha256: null,
      verify_peer_cert_by_name: null,
      wireguard_overrides: null,
      subscription_templates: null,
      final_mask_settings: null,
      cipher_suites: null,
    },
    {
      id: 2,
      native_tunnel_id: null,
      remark: 'TR CDN Fallback',
      address: ['tr-cdn.example.net'],
      inbound_tag: 'TROJAN_WS',
      port: 443,
      sni: ['tr-cdn.example.net'],
      host: ['tr-cdn.example.net'],
      path: '/ws-trojan',
      security: 'tls',
      alpn: ['http/1.1'],
      fingerprint: 'firefox',
      allowinsecure: false,
      is_disabled: false,
      http_headers: null,
      transport_settings: null,
      mux_settings: null,
      fragment_settings: null,
      noise_settings: null,
      random_user_agent: false,
      use_sni_as_host: false,
      vless_route: null,
      priority: 2,
      status: null,
      ech: null,
      pinned_peer_cert_sha256: null,
      verify_peer_cert_by_name: null,
      wireguard_overrides: null,
      subscription_templates: null,
      final_mask_settings: null,
      cipher_suites: null,
    },
  ] as StoredHost[],

  users: [
    {
      id: 1,
      username: 'alex_pro',
      status: 'active',
      used_traffic: 48 * 1024 ** 3,
      lifetime_used_traffic: 162 * 1024 ** 3,
      data_limit: 200 * 1024 ** 3,
      data_limit_reset_strategy: 'month',
      expire: daysFromNowIso(28),
      on_hold_expire_duration: null,
      on_hold_timeout: null,
      group_ids: [1, 2],
      auto_delete_in_days: null,
      hwid_limit: 3,
      note: 'Annual multi-device plan',
      next_plan: null,
      proxy_settings: {
        vless: { id: '3b4827f2-4a2b-4c5d-8e6f-111122223331', flow: 'xtls-rprx-vision' },
        trojan: { password: 'tr_alex_pro_9988' },
        shadowsocks: { password: 'ss_alex_pro_7766', method: 'aes-256-gcm' },
      },
      created_at: hoursAgoIso(480),
      edit_at: hoursAgoIso(12),
      online_at: new Date(Date.now() - 25_000).toISOString(),
      sub_updated_at: hoursAgoIso(2),
      sub_last_user_agent: 'v2rayNG/1.9.32',
      subscription_token: 'sub_alex_pro_01',
      subscription_url: '/sub/sub_alex_pro_01',
      admin_id: 1,
    },
    {
      id: 2,
      username: 'sara_vip',
      status: 'active',
      used_traffic: 92 * 1024 ** 3,
      lifetime_used_traffic: 310 * 1024 ** 3,
      data_limit: 250 * 1024 ** 3,
      data_limit_reset_strategy: 'month',
      expire: daysFromNowIso(14),
      on_hold_expire_duration: null,
      on_hold_timeout: null,
      group_ids: [1],
      auto_delete_in_days: null,
      hwid_limit: 5,
      note: 'iOS Streisand user',
      next_plan: null,
      proxy_settings: {
        vless: { id: '3b4827f2-4a2b-4c5d-8e6f-111122223332', flow: 'xtls-rprx-vision' },
        trojan: { password: 'tr_sara_vip_2211' },
      },
      created_at: hoursAgoIso(360),
      edit_at: hoursAgoIso(6),
      online_at: new Date(Date.now() - 18_000).toISOString(),
      sub_updated_at: hoursAgoIso(1),
      sub_last_user_agent: 'Streisand/2.4.1 (iOS)',
      subscription_token: 'sub_sara_vip_02',
      subscription_url: '/sub/sub_sara_vip_02',
      admin_id: 1,
    },
    {
      id: 3,
      username: 'daniel_wg',
      status: 'active',
      used_traffic: 31 * 1024 ** 3,
      lifetime_used_traffic: 84 * 1024 ** 3,
      data_limit: 150 * 1024 ** 3,
      data_limit_reset_strategy: 'no_reset',
      expire: daysFromNowIso(45),
      on_hold_expire_duration: null,
      on_hold_timeout: null,
      group_ids: [2],
      auto_delete_in_days: null,
      hwid_limit: 2,
      note: 'Gaming WireGuard + SmartDNS',
      next_plan: null,
      proxy_settings: {
        vless: { id: '3b4827f2-4a2b-4c5d-8e6f-111122223333', flow: 'xtls-rprx-vision' },
        wireguard: { private_key: 'cGxhY2Vob2xkZXJfdXNlcl93Z19wcml2YXRlX2tleQ==', public_key: 'cGxhY2Vob2xkZXJfdXNlcl93Z19wdWJsaWNfa2V5' },
      },
      created_at: hoursAgoIso(240),
      edit_at: null,
      online_at: hoursAgoIso(3),
      sub_updated_at: hoursAgoIso(4),
      sub_last_user_agent: 'sing-box/1.11.0 (Windows)',
      subscription_token: 'sub_daniel_wg_03',
      subscription_url: '/sub/sub_daniel_wg_03',
      admin_id: 1,
    },
    {
      id: 4,
      username: 'mira_onhold',
      status: 'on_hold',
      used_traffic: 0,
      lifetime_used_traffic: 0,
      data_limit: 100 * 1024 ** 3,
      data_limit_reset_strategy: 'no_reset',
      expire: null,
      on_hold_expire_duration: 30 * 86400,
      on_hold_timeout: daysFromNowIso(60),
      group_ids: [1],
      auto_delete_in_days: null,
      hwid_limit: null,
      note: 'Starts timer on first connection',
      next_plan: null,
      proxy_settings: {
        vless: { id: '3b4827f2-4a2b-4c5d-8e6f-111122223334', flow: 'xtls-rprx-vision' },
      },
      created_at: hoursAgoIso(120),
      edit_at: null,
      online_at: null,
      sub_updated_at: null,
      sub_last_user_agent: null,
      subscription_token: 'sub_mira_onhold_04',
      subscription_url: '/sub/sub_mira_onhold_04',
      admin_id: 2,
    },
    {
      id: 5,
      username: 'nina_limited',
      status: 'limited',
      used_traffic: 50 * 1024 ** 3,
      lifetime_used_traffic: 50 * 1024 ** 3,
      data_limit: 50 * 1024 ** 3,
      data_limit_reset_strategy: 'month',
      expire: daysFromNowIso(9),
      on_hold_expire_duration: null,
      on_hold_timeout: null,
      group_ids: [1],
      auto_delete_in_days: null,
      hwid_limit: 2,
      note: 'Quota reached',
      next_plan: null,
      proxy_settings: {
        vless: { id: '3b4827f2-4a2b-4c5d-8e6f-111122223335', flow: 'xtls-rprx-vision' },
      },
      created_at: hoursAgoIso(400),
      edit_at: hoursAgoIso(18),
      online_at: hoursAgoIso(5),
      sub_updated_at: hoursAgoIso(8),
      sub_last_user_agent: 'FlClash/0.8.80 (Android)',
      subscription_token: 'sub_nina_limited_05',
      subscription_url: '/sub/sub_nina_limited_05',
      admin_id: 2,
    },
    {
      id: 6,
      username: 'ali_expired',
      status: 'expired',
      used_traffic: 18 * 1024 ** 3,
      lifetime_used_traffic: 44 * 1024 ** 3,
      data_limit: 100 * 1024 ** 3,
      data_limit_reset_strategy: 'no_reset',
      expire: hoursAgoIso(72),
      on_hold_expire_duration: null,
      on_hold_timeout: null,
      group_ids: [1],
      auto_delete_in_days: 30,
      hwid_limit: null,
      note: 'Needs renewal',
      next_plan: null,
      proxy_settings: {
        vless: { id: '3b4827f2-4a2b-4c5d-8e6f-111122223336', flow: 'xtls-rprx-vision' },
      },
      created_at: hoursAgoIso(800),
      edit_at: null,
      online_at: hoursAgoIso(75),
      sub_updated_at: hoursAgoIso(90),
      sub_last_user_agent: 'Shadowrocket/2.2.58 (iOS)',
      subscription_token: 'sub_ali_expired_06',
      subscription_url: '/sub/sub_ali_expired_06',
      admin_id: 1,
    },
  ] as StoredUser[],

  userHwids: new Map<number, Array<{ hwid: string; device_name: string | null; os: string | null; user_agent: string | null; created_at: string; last_seen_at: string }>>([
    [
      1,
      [
        { hwid: 'HWID-8F29A1C0-IOS', device_name: 'iPhone 15 Pro', os: 'iOS 18.2', user_agent: 'Streisand/2.4.1', created_at: hoursAgoIso(200), last_seen_at: hoursAgoIso(1) },
        { hwid: 'HWID-4B12D9E3-WIN', device_name: 'Workstation PC', os: 'Windows 11', user_agent: 'v2rayN/7.10.2', created_at: hoursAgoIso(140), last_seen_at: hoursAgoIso(4) },
      ],
    ],
    [
      2,
      [
        { hwid: 'HWID-7C31E5F2-IOS', device_name: 'iPad Air', os: 'iPadOS 18', user_agent: 'Streisand/2.4.1', created_at: hoursAgoIso(180), last_seen_at: hoursAgoIso(2) },
      ],
    ],
  ]),

  userTemplates: [
    {
      id: 1,
      name: 'Standard 100GB / 30 Days',
      data_limit: 100 * 1024 ** 3,
      hwid_limit: 3,
      expire_duration: 30 * 86400,
      username_prefix: 'pg_',
      username_suffix: null,
      group_ids: [1],
      extra_settings: null,
      status: 'active',
      reset_usages: false,
      on_hold_timeout: null,
      data_limit_reset_strategy: 'month',
      is_disabled: false,
    },
    {
      id: 2,
      name: 'Gaming Pro 250GB / 60 Days',
      data_limit: 250 * 1024 ** 3,
      hwid_limit: 5,
      expire_duration: 60 * 86400,
      username_prefix: 'vip_',
      username_suffix: null,
      group_ids: [1, 2],
      extra_settings: null,
      status: 'on_hold',
      reset_usages: false,
      on_hold_timeout: 30 * 86400,
      data_limit_reset_strategy: 'no_reset',
      is_disabled: false,
    },
  ] as StoredUserTemplate[],

  clientTemplates: [
    {
      id: 1,
      name: 'Sing-box Default',
      template_type: 'sing-box',
      content: JSON.stringify({ dns: { servers: [{ tag: 'remote', address: 'https://1.1.1.1/dns-query' }] } }, null, 2),
      is_default: true,
      is_system: false,
    },
    {
      id: 2,
      name: 'Clash Meta Clean',
      template_type: 'clash-meta',
      content: 'mixed-port: 7890\nallow-lan: false\nmode: rule\nlog-level: info\n',
      is_default: true,
      is_system: false,
    },
  ] as StoredClientTemplate[],

  apiKeys: [
    {
      id: 1,
      name: 'Billing Bot Integration',
      note: 'Automated provisioning key',
      prefix: 'pg_key_4f9b',
      raw_key: 'pg_key_4f9b7e20-1c3a-4b5d-8e6f-0123456789ab',
      permissions: { users: fullCrud(), templates: { read: true, read_simple: true } },
      inherit_permissions: false,
      expire_date: daysFromNowIso(180),
      max_requests: 100000,
      requests_used: 1420,
      delete_on_limit: false,
      admin_id: 1,
      is_expired: false,
      is_usable: true,
      created_at: hoursAgoIso(300),
      last_used_at: hoursAgoIso(1),
    },
  ] as StoredApiKey[],

  settings: {
    general: {
      default_flow: 'xtls-rprx-vision',
      default_method: 'aes-256-gcm',
      auth_storage_expiry: 86400,
      min_node_version: 'v1.0.0',
    },
    subscription: {
      url_prefix: '',
      update_interval: 12,
      support_link: 'https://t.me/Pasar_Guard',
      profile_title: 'PasarGuard Plus',
      announce: 'Connected via PasarGuard Plus',
      announce_url: '',
      shuffle_configs: false,
      keep_alive: true,
      custom_response_headers: {},
      templates: {},
      applications: [],
      rules: [],
    },
    hwid: {
      enabled: true,
      forced: false,
      require_hwid_for_manual_sub: false,
      fallback_limit: 3,
      min_limit: 1,
      max_limit: 10,
    },
    notification_enable: {
      admin: { create: true, modify: true, delete: true, reset_usage: true },
      user: { create: true, modify: true, delete: true, expired: true, limited: true },
    },
    notification_settings: {
      notify_days_left: [3, 1],
      notify_usage_percent: [80, 95],
      proxy_url: null,
    },
    telegram: {
      enable: false,
      token: '',
      webhook_url: '',
      webhook_secret: '',
      admin_ids: [100200300],
      mini_app_login: true,
    },
    webhook: {
      enable: false,
      webhooks: [],
      proxy_url: null,
    },
    cleanup: {
      auto_delete_expired_users: false,
      auto_delete_days: 30,
      usage_retention_days: 90,
    },
  } as Record<string, unknown>,

  // Native Plus: Tunnels & Failover & Discovery
  tunnels: [
    {
      id: 1,
      name: 'Tehran-DE Paqet Bridge',
      source_node_id: 2,
      exit_node_id: 1,
      inbound_tags: ['VLESS_REALITY'],
      domains: ['de-edge.example.net'],
      port: 24430,
      enabled: true,
      health_enabled: true,
      transport: 'paqet',
      paqet_options: {
        mode: 'client',
        kcp_mode: 'fast',
        mtu: 1350,
        conn: 4,
      },
      carrier_options: {},
      engine_id: 'paqet',
      revision: 3,
      applied_revision: 3,
      networks: ['tcp', 'udp'],
    },
    {
      id: 2,
      name: 'Edge Backhaul Relay',
      source_node_id: 2,
      exit_node_id: 3,
      inbound_tags: ['SS_2022'],
      domains: [],
      port: 28443,
      enabled: true,
      health_enabled: true,
      transport: 'backhaul',
      paqet_options: {},
      carrier_options: {
        nodelay: true,
        channel_size: 2048,
        mux_con: 8,
      },
      engine_id: 'backhaul',
      revision: 2,
      applied_revision: 2,
      networks: ['tcp'],
    },
  ] as StoredTunnel[],

  tunnelAuditLogs: [
    {
      id: 1,
      created_at: hoursAgoIso(6),
      actor: 'admin',
      action: 'apply_tunnels',
      target_id: '1',
      details: { revision: 3, ok: true, transport: 'paqet' },
    },
    {
      id: 2,
      created_at: hoursAgoIso(2),
      actor: 'admin',
      action: 'health_probe',
      target_id: '2',
      details: { latency_ms: 41, status: 'healthy' },
    },
  ] as StoredTunnelAudit[],

  tunnelFailovers: [
    {
      source_node_id: 2,
      primary_tunnel_id: 1,
      backup_tunnel_ids: [2],
      auto_switch: true,
      probe_interval_sec: 15,
      failure_threshold: 3,
      active_tunnel_id: 1,
    },
  ] as Array<Record<string, unknown>>,

  discoveryReservations: [] as Array<Record<string, unknown>>,
  provisionJobs: [] as Array<Record<string, unknown>>,

  // Native Plus: DNS & SmartDNS
  dnsPolicies: [
    {
      id: 1,
      name: 'Global Unblock & AdGuard',
      revision: 2,
      rules: [
        { domain: 'openai.com', action: 'proxy', target: 'direct', services: ['chatgpt'] },
        { domain: 'youtube.com', action: 'proxy', target: 'direct', services: ['youtube'] },
      ],
    },
    {
      id: 2,
      name: 'Gaming Low-Latency',
      revision: 1,
      rules: [
        { domain: 'riotgames.com', action: 'proxy', target: 'direct', services: ['valorant'] },
      ],
    },
  ] as StoredDNSPolicy[],

  dnsAccounts: [
    {
      id: 1,
      user_id: 1,
      username: 'alex_pro',
      policy_id: 1,
      policy_name: 'Global Unblock & AdGuard',
      enabled: true,
      eligible: true,
      query_limit: 100000,
      qps: 30,
      period: 86400,
      queries: 14820,
      request_bytes: 2840000,
      response_bytes: 8920000,
      errors: 12,
      blocked: 940,
      relay_rps: 50,
      relay_enabled: true,
      relay_byte_limit: 50 * 1024 ** 3,
      relay_bytes: 6 * 1024 ** 3,
      relay_reserved: 0,
      relay_uncertain_bytes: 0,
      token: 'dns_tok_alex_01',
      created_at: hoursAgoIso(240),
    },
    {
      id: 2,
      user_id: 3,
      username: 'daniel_wg',
      policy_id: 2,
      policy_name: 'Gaming Low-Latency',
      enabled: true,
      eligible: true,
      query_limit: 250000,
      qps: 60,
      period: 86400,
      queries: 42190,
      request_bytes: 6120000,
      response_bytes: 18400000,
      errors: 4,
      blocked: 310,
      relay_rps: 100,
      relay_enabled: true,
      relay_byte_limit: 100 * 1024 ** 3,
      relay_bytes: 19 * 1024 ** 3,
      relay_reserved: 0,
      relay_uncertain_bytes: 0,
      token: 'dns_tok_daniel_02',
      created_at: hoursAgoIso(180),
    },
  ] as StoredDNSAccount[],

  dnsLeases: [
    {
      id: 1,
      account_id: 1,
      ip: '203.0.113.45',
      label: 'Home Fiber',
      created_at: hoursAgoIso(48),
      expires_at: daysFromNowIso(7),
    },
    {
      id: 2,
      account_id: 2,
      ip: '198.51.100.22',
      label: 'Gaming PC',
      created_at: hoursAgoIso(24),
      expires_at: daysFromNowIso(14),
    },
  ] as StoredDNSLease[],

  dnsAuditLogs: [
    {
      id: 1,
      created_at: hoursAgoIso(3),
      actor: 'admin',
      action: 'create_dns_account',
      target: 'alex_pro',
      detail: 'Attached policy Global Unblock & AdGuard',
    },
  ] as Array<{ id: number } & Record<string, unknown>>,

  smartDnsRoutes: [
    {
      id: 1,
      node_id: 1,
      policy_id: 1,
      domain: 'openai.com',
      public_address: '185.199.108.153',
      bind_address: '0.0.0.0',
      tcp_ports: [80, 443],
      udp_ports: [443],
      mode: 'sniff',
      enabled: true,
      revision: 1,
      egress_tunnel_id: 1,
    },
  ] as StoredSmartDNSRoute[],

  smartDnsNodeSettings: new Map<number, { enabled: boolean; bind_address: string; port: number; revision: number; applied_revision: number }>([
    [1, { enabled: true, bind_address: '0.0.0.0', port: 5353, revision: 1, applied_revision: 1 }],
    [2, { enabled: true, bind_address: '0.0.0.0', port: 5353, revision: 1, applied_revision: 1 }],
  ]),
}

export function buildAdminDetails(admin: StoredAdmin) {
  const role = store.roles.find(r => r.id === admin.role_id) ?? store.roles[0]
  const totalUsers = store.users.filter(u => u.admin_id === admin.id).length
  return {
    id: admin.id,
    username: admin.username,
    telegram_id: admin.telegram_id,
    discord_webhook: admin.discord_webhook,
    sub_domain: admin.sub_domain,
    profile_title: admin.profile_title,
    support_url: admin.support_url,
    custom_variables: admin.custom_variables,
    notification_enable: admin.notification_enable,
    total_users: totalUsers,
    used_traffic: admin.used_traffic,
    data_limit: admin.data_limit,
    status: admin.status,
    sub_template: admin.sub_template,
    lifetime_used_traffic: admin.lifetime_used_traffic,
    note: admin.note,
    role: {
      id: role.id,
      name: role.name,
      is_owner: role.is_owner,
      permissions: role.permissions,
      limits: role.limits,
      features: role.features,
      access: role.access,
      hwid: role.hwid,
      disabled_when_limited: role.disabled_when_limited,
      disconnect_users_when_limited: role.disconnect_users_when_limited,
      disconnect_users_when_disabled: role.disconnect_users_when_disabled,
    },
    permission_overrides: admin.permission_overrides,
    is_disabled: admin.is_disabled,
    is_limited: admin.is_limited,
  }
}

export function buildUserResponse(user: StoredUser) {
  const admin = store.admins.find(a => a.id === user.admin_id)
  return {
    id: user.id,
    username: user.username,
    status: user.status,
    used_traffic: user.used_traffic,
    lifetime_used_traffic: user.lifetime_used_traffic,
    data_limit: user.data_limit,
    data_limit_reset_strategy: user.data_limit_reset_strategy,
    expire: user.expire,
    on_hold_expire_duration: user.on_hold_expire_duration,
    on_hold_timeout: user.on_hold_timeout,
    group_ids: user.group_ids,
    auto_delete_in_days: user.auto_delete_in_days,
    hwid_limit: user.hwid_limit,
    note: user.note,
    next_plan: user.next_plan,
    proxy_settings: user.proxy_settings,
    created_at: user.created_at,
    edit_at: user.edit_at,
    online_at: user.online_at,
    sub_updated_at: user.sub_updated_at,
    sub_last_user_agent: user.sub_last_user_agent,
    subscription_url: user.subscription_url,
    admin: admin ? { id: admin.id, username: admin.username } : null,
  }
}

export function buildSystemResourceStats() {
  const memTotal = os.totalmem() || 8 * 1024 ** 3
  const memFree = os.freemem() || 4 * 1024 ** 3
  const memUsed = Math.max(0, memTotal - memFree)
  const cpuCores = os.cpus()?.length || 4
  const load = os.loadavg()[0] || 0.45
  const cpuUsage = Math.min(99, Math.max(4, Number(((load / cpuCores) * 100).toFixed(1))))

  return {
    version: '5.6.0-plus',
    uptime_seconds: Math.floor((Date.now() - store.startedAt) / 1000) + 172800,
    mem_total: memTotal,
    mem_used: memUsed,
    disk_total: 120 * 1024 ** 3,
    disk_used: 38 * 1024 ** 3,
    cpu_cores: cpuCores,
    cpu_usage: cpuUsage,
  }
}

export function buildSystemUsersStats(adminUsername?: string) {
  let users = store.users
  if (adminUsername) {
    const targetAdmin = store.admins.find(a => a.username === adminUsername)
    if (targetAdmin) {
      users = users.filter(u => u.admin_id === targetAdmin.id)
    }
  }
  const now = Date.now()
  const onlineUsers = users.filter(u => u.online_at && now - new Date(u.online_at).getTime() < 120_000).length
  const incoming = store.nodes.reduce((sum, n) => sum + (n.uplink || 0), 0)
  const outgoing = store.nodes.reduce((sum, n) => sum + (n.downlink || 0), 0)

  return {
    total_user: users.length,
    online_users: onlineUsers,
    active_users: users.filter(u => u.status === 'active').length,
    on_hold_users: users.filter(u => u.status === 'on_hold').length,
    disabled_users: users.filter(u => u.status === 'disabled').length,
    expired_users: users.filter(u => u.status === 'expired').length,
    limited_users: users.filter(u => u.status === 'limited').length,
    incoming_bandwidth: incoming,
    outgoing_bandwidth: outgoing,
  }
}

export function getAllInboundsFromCores() {
  const list: Array<{ tag: string; protocol: string; network?: string | null; security?: string | null; fingerprint?: string | null }> = []
  const seen = new Set<string>()
  for (const core of store.cores) {
    const cfg = core.config as { inbounds?: Array<Record<string, unknown>> }
    if (Array.isArray(cfg?.inbounds)) {
      for (const inbound of cfg.inbounds) {
        const tag = String(inbound.tag || '')
        if (!tag || seen.has(tag)) continue
        seen.add(tag)
        const stream = (inbound.streamSettings as Record<string, unknown> | undefined) ?? {}
        const reality = (stream.realitySettings as Record<string, unknown> | undefined) ?? {}
        list.push({
          tag,
          protocol: String(inbound.protocol || 'vless'),
          network: stream.network ? String(stream.network) : 'tcp',
          security: stream.security ? String(stream.security) : 'none',
          fingerprint: reality.fingerprint ? String(reality.fingerprint) : null,
        })
      }
    }
  }
  return list
}

export function generateTimeBuckets(startIso: string | undefined, endIso: string | undefined, period: string = 'hour') {
  const end = endIso ? new Date(endIso) : new Date()
  const endMs = Number.isNaN(end.getTime()) ? Date.now() : end.getTime()
  const stepMs =
    period === 'minute'
      ? 60_000 * 10
      : period === 'hour'
        ? 3600_000
        : period === 'month'
          ? 86400_000 * 30
          : 86400_000
  const defaultSpan = period === 'minute' ? 6 * 3600_000 : period === 'hour' ? 24 * 3600_000 : 14 * 86400_000
  const start = startIso ? new Date(startIso) : new Date(endMs - defaultSpan)
  const startMs = Number.isNaN(start.getTime()) ? endMs - defaultSpan : start.getTime()

  const buckets: string[] = []
  const maxPoints = 48
  const actualStep = Math.max(stepMs, Math.floor((endMs - startMs) / maxPoints) || stepMs)
  for (let ts = startMs; ts <= endMs && buckets.length < maxPoints; ts += actualStep) {
    buckets.push(new Date(ts).toISOString())
  }
  if (buckets.length === 0) buckets.push(new Date(endMs).toISOString())
  return {
    start: new Date(startMs).toISOString(),
    end: new Date(endMs).toISOString(),
    period,
    buckets,
  }
}

export function newToken(adminId: number): string {
  const token = `pg_tok_${adminId}_${crypto.randomBytes(16).toString('hex')}`
  store.tokens.set(token, adminId)
  return token
}

export function nextId(items: Array<{ id: number }>): number {
  return items.reduce((max, item) => Math.max(max, item.id || 0), 0) + 1
}
