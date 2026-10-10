import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client.js'

/** State and actions shared by the seven presentation sections in App.vue. */
export function useParkingWorkspace() {
  const navigation = [
    { id: 'overview', label: '运营总览', icon: 'dashboard', hint: '每一次抵达，从这里开始。' },
    { id: 'entry', label: '入场与寻车', icon: 'car', hint: '登记车辆，找到属于它的位置。' },
    { id: 'passes', label: '预约与月卡', icon: 'ticket', hint: '提前留好车位，让出行从容一点。' },
    { id: 'charging', label: '充电服务', icon: 'bolt', hint: '停好车，也为下一程充好电。' },
    { id: 'billing', label: '出场结算', icon: 'wallet', hint: '费用清楚，出场顺畅。' },
    { id: 'resources', label: '车位资源', icon: 'parking', hint: '楼层、区域与车位，一目了然。' },
    { id: 'analytics', label: '运营报表', icon: 'chart', hint: '从真实车流，读懂运营节奏。' },
  ]
  const page = ref('overview')
  const currentPage = computed(() => navigation.find((item) => item.id === page.value))
  const types = {
    NORMAL: '普通车位',
    ACCESSIBLE: '无障碍车位',
    CHARGING: '充电车位',
    RESERVATION: '预约车位',
  }
  const states = {
    AVAILABLE: '可用',
    OCCUPIED: '占用',
    OUT_OF_SERVICE: '停用',
    IN_USE: '使用中',
    PARKED: '在场',
    EXIT_PENDING_PAYMENT: '待支付',
    PAID_PENDING_RELEASE: '已付待释放',
    CLOSED: '已出场',
    PENDING_PAYMENT: '待预付',
    CONFIRMED: '已确认',
    USED: '已核销',
    CANCELLED: '已取消',
    EXPIRED: '已过期',
    ACTIVE: '使用中',
    FINISHED: '已结束',
    PAID: '已支付',
    UNPAID: '待支付',
    REQUESTED: '已申请',
  }
  const label = (state) => states[state] || state
  const feeNames = {
    PARKING_DAY: '自然日停车费',
    LOST_CARD: '丢卡附加费',
    LONG_STAY: '超长停车附加费',
    DISCOUNT: '停车优惠',
    PREPAID_APPLIED: '预付抵扣',
    PREPAID_REFUND: '预付差额退款',
    CHARGING: '充电费用',
  }

  const services = ref([
    { name: '车位资源', path: '/api/v1/spaces/status', state: '待检查' },
    { name: '出入场管理', path: '/api/v1/access/status', state: '待检查' },
    { name: '计费支付', path: '/api/v1/billing/status', state: '待检查' },
    { name: '预约月卡', path: '/api/v1/passes/status', state: '待检查' },
    { name: '运营分析', path: '/api/v1/analytics/status', state: '待检查' },
  ])
  const busy = ref(false)
  const spaces = ref([])
  const chargers = ref([])
  const session = ref(null)
  const bill = ref(null)
  const report = ref(null)
  const reservation = ref(null)
  const monthly = ref(null)
  const monthlyLedger = ref([])
  const charging = ref(null)
  const invoiceResult = ref(null)
  const exitResult = ref(null)
  const dashboardReport = ref(null)
  const dashboardError = ref('')
  const resourcesReady = ref(false)
  const resourceError = ref('')
  const updatedAt = ref('')
  const metrics = computed(() => ({
    total: spaces.value.length,
    available: spaces.value.filter((s) => s.status === 'AVAILABLE').length,
    occupied: spaces.value.filter((s) => s.status === 'OCCUPIED').length,
    offline: spaces.value.filter((s) => s.status === 'OUT_OF_SERVICE').length,
  }))
  const occupancy = computed(() =>
    metrics.value.total ? Math.round((metrics.value.occupied / metrics.value.total) * 100) : 0,
  )
  const lastAction = ref('')
  const noticeType = ref('success')
  const connectedCount = computed(() => services.value.filter((s) => s.state === '已连接').length)
  const settlementStep = computed(() =>
    session.value?.status === 'CLOSED' || exitResult.value?.status === 'CLOSED'
      ? 4
      : bill.value?.status === 'PAID'
        ? 3
        : bill.value
          ? 1
          : 0,
  )
  const typeIcon = (type) =>
    ({ NORMAL: 'parking', ACCESSIBLE: 'accessible', CHARGING: 'bolt', RESERVATION: 'ticket' })[type]
  const entry = reactive({ plateNumber: '', spaceType: 'NORMAL', reservationId: '', entryTime: '' })
  const search = reactive({ plateNumber: '' })
  const exit = reactive({ sessionId: '', exitTime: '', exceptionType: 'NONE', operator: '' })
  const reserve = reactive({ plateNumber: '', spaceId: '', startTime: '', endTime: '' })
  const pass = reactive({ plateNumber: '', startTime: '' })
  const charge = reactive({
    chargerId: '',
    parkingSessionId: '',
    startTime: '',
    endTime: '',
    energyKwh: '',
  })
  const adminSpace = reactive({
    type: 'NORMAL',
    floor: '',
    zone: '',
    number: '',
    spaceId: '',
    status: 'OUT_OF_SERVICE',
  })
  const invoice = reactive({ billId: '', invoiceTitle: '', taxNumber: '' })
  const analytics = reactive({ from: '', to: '', granularity: 'HOUR' })
  const output = ref('操作结果会显示在这里。')

  function iso(value) {
    if (!value) return undefined
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) throw new Error('请输入有效时间')
    return date.toISOString()
  }
  function money(cents) {
    return cents == null ? '—' : `¥${(Number(cents) / 100).toFixed(2)}`
  }
  function dateTime(value) {
    return value
      ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
      : '—'
  }
  function localInput(date) {
    const offset = date.getTimezoneOffset() * 60000
    return new Date(date.getTime() - offset).toISOString().slice(0, 23)
  }

  async function action(job, showPaymentStatus = false) {
    if (busy.value) return
    busy.value = true
    try {
      const result = await job()
      output.value = JSON.stringify(result, null, 2)
      const status = showPaymentStatus ? result?.status : undefined
      noticeType.value = ['PAID_PENDING_RELEASE', 'EXIT_PENDING_PAYMENT'].includes(status)
        ? 'warning'
        : 'success'
      lastAction.value =
        status === 'PAID_PENDING_RELEASE'
          ? '支付已成功，车位尚未释放；请重试完成出场。'
          : status === 'EXIT_PENDING_PAYMENT'
            ? '支付未成功，账单保留待支付状态，可以重试。'
            : '操作完成，结果已更新。'
      ElMessage({ type: noticeType.value, message: lastAction.value })
      return result
    } catch (error) {
      output.value = error.message
      noticeType.value = 'error'
      lastAction.value = error.message
      ElMessage.error(error.message)
    } finally {
      busy.value = false
    }
  }
  async function checkServices() {
    await Promise.all(
      services.value.map(async (service) => {
        try {
          await api(service.path)
          service.state = '已连接'
        } catch {
          service.state = '未连接'
        }
      }),
    )
  }
  async function loadResources() {
    try {
      const [spaceData, chargerData] = await Promise.all([
        api('/api/v1/spaces'),
        api('/api/v1/spaces/chargers'),
      ])
      spaces.value = spaceData
      chargers.value = chargerData
      resourcesReady.value = true
      resourceError.value = ''
      updatedAt.value = dateTime(new Date())
    } catch (error) {
      resourceError.value = `资源刷新失败：${error.message}`
    }
  }
  async function refreshOverview() {
    await Promise.all([checkServices(), loadResources(), loadTrafficPreview()])
  }
  async function loadTrafficPreview() {
    try {
      const to = new Date(),
        from = new Date(to.getTime() - 24 * 60 * 60 * 1000)
      dashboardReport.value = await api(
        `/api/v1/analytics/traffic-preview?${new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), granularity: 'HOUR' })}`,
      )
      dashboardError.value = ''
    } catch (error) {
      dashboardError.value = `车流统计暂不可用：${error.message}`
    }
  }
  async function createSpace() {
    await action(async () => {
      const data = await api('/api/v1/spaces', 'POST', {
        type: adminSpace.type,
        floor: adminSpace.floor,
        zone: adminSpace.zone,
        number: adminSpace.number,
      })
      await loadResources()
      return data
    })
  }
  async function setSpaceStatus() {
    await action(async () => {
      const data = await api(`/api/v1/spaces/${adminSpace.spaceId}/service-status`, 'PATCH', {
        status: adminSpace.status,
      })
      await loadResources()
      return data
    })
  }
  async function createReservation() {
    await action(async () => {
      reservation.value = await api('/api/v1/passes/reservations', 'POST', {
        plateNumber: reserve.plateNumber,
        spaceId: reserve.spaceId,
        startTime: iso(reserve.startTime),
        endTime: iso(reserve.endTime),
      })
      entry.reservationId = reservation.value.reservationId
      entry.plateNumber = reserve.plateNumber
      entry.spaceType = 'RESERVATION'
      return reservation.value
    })
  }
  async function payReservation(result) {
    await action(async () => {
      if (!reservation.value) throw new Error('请先创建预约')
      reservation.value = {
        ...reservation.value,
        ...(await api(
          `/api/v1/passes/reservations/${reservation.value.reservationId}/pay`,
          'POST',
          { simulatedResult: result },
        )),
      }
      return reservation.value
    })
  }
  async function cancelReservation() {
    await action(async () => {
      if (!reservation.value) throw new Error('请先创建预约')
      reservation.value = {
        ...reservation.value,
        ...(await api(
          `/api/v1/passes/reservations/${reservation.value.reservationId}/cancel`,
          'POST',
          {},
        )),
      }
      return reservation.value
    })
  }
  async function createPass() {
    await action(async () => {
      monthly.value = await api('/api/v1/passes/monthly-passes', 'POST', {
        plateNumber: pass.plateNumber,
        startTime: iso(pass.startTime),
      })
      monthlyLedger.value = await api(
        `/api/v1/passes/monthly-passes/${monthly.value.monthlyPassId}/ledger`,
      )
      return monthly.value
    })
  }
  async function refreshPass() {
    await action(async () => {
      if (!monthly.value) throw new Error('请先创建月卡')
      const id = monthly.value.monthlyPassId
      monthly.value = await api(`/api/v1/passes/monthly-passes/${id}`)
      monthlyLedger.value = await api(`/api/v1/passes/monthly-passes/${id}/ledger`)
      return { monthly: monthly.value, ledger: monthlyLedger.value }
    })
  }
  async function enter() {
    await action(async () => {
      const data = await api('/api/v1/access/entries', 'POST', {
        plateNumber: entry.plateNumber,
        spaceType: entry.spaceType,
        reservationId:
          entry.spaceType === 'RESERVATION' ? entry.reservationId || undefined : undefined,
        entryTime: iso(entry.entryTime),
      })
      await selectSession(data)
      await loadResources()
      return session.value
    })
  }
  async function locate() {
    return action(async () => {
      await selectSession(
        await api(`/api/v1/access/locate?plateNumber=${encodeURIComponent(search.plateNumber)}`),
      )
      return session.value
    })
  }
  async function findFromOverview() {
    if (await locate()) page.value = 'entry'
  }
  async function selectSession(data) {
    // Clear the previous vehicle's receipt before loading a new authoritative snapshot.
    bill.value = null
    exitResult.value = null
    if (session.value?.parkingSessionId !== data.parkingSessionId) {
      charging.value = null
      charge.startTime = ''
      charge.endTime = ''
      charge.energyKwh = ''
      invoiceResult.value = null
    }
    session.value = data
    exit.sessionId = data.parkingSessionId
    exit.exitTime = data.exitTime ? localInput(new Date(data.exitTime)) : ''
    exit.exceptionType = data.exceptionType || 'NONE'
    exit.operator = data.operator || ''
    charge.parkingSessionId = data.parkingSessionId
    charge.chargerId = chargers.value.find((c) => c.spaceId === data.spaceId)?.chargerId || ''
    search.plateNumber = data.plateNumber
    invoice.billId = data.billId || ''
    bill.value = data.billId ? await api(`/api/v1/billing/bills/${data.billId}`) : null
    exitResult.value = data.billId
      ? { parkingSessionId: data.parkingSessionId, status: data.status }
      : null
  }
  async function startCharge() {
    await action(async () => {
      if (!charge.startTime) charge.startTime = localInput(new Date())
      charging.value = await api('/api/v1/spaces/charging-sessions', 'POST', {
        chargerId: charge.chargerId,
        parkingSessionId: charge.parkingSessionId,
        startTime: iso(charge.startTime || new Date().toISOString()),
      })
      await loadResources()
      return charging.value
    })
  }
  async function finishCharge() {
    await action(async () => {
      if (!charging.value) throw new Error('请先开始充电')
      if (!charge.endTime) charge.endTime = localInput(new Date())
      charging.value = await api(
        `/api/v1/spaces/charging-sessions/${charging.value.chargingSessionId}/finish`,
        'POST',
        { endTime: iso(charge.endTime || new Date().toISOString()), energyKwh: charge.energyKwh },
      )
      await loadResources()
      return charging.value
    })
  }
  async function requestExit() {
    await action(async () => {
      if (!exit.exitTime) exit.exitTime = localInput(new Date())
      const data = await api(`/api/v1/access/${exit.sessionId}/exit-requests`, 'POST', {
        exitTime: iso(exit.exitTime || new Date().toISOString()),
        exceptionType: exit.exceptionType,
        operator: exit.operator || undefined,
      })
      bill.value = await api(`/api/v1/billing/bills/${data.billId}`)
      invoice.billId = data.billId
      exitResult.value = data
      if (session.value?.parkingSessionId === data.parkingSessionId)
        session.value.status = data.status
      return { exit: data, bill: bill.value }
    })
  }
  async function completeExit(result) {
    await action(async () => {
      const data = await api(`/api/v1/access/${exit.sessionId}/complete-exit`, 'POST', {
        simulatedResult: result,
      })
      exitResult.value = data
      if (session.value?.parkingSessionId === data.parkingSessionId)
        session.value.status = data.status
      await loadResources()
      if (bill.value) bill.value = await api(`/api/v1/billing/bills/${bill.value.billId}`)
      return data
    }, true)
  }
  async function applyInvoice() {
    await action(async () => {
      invoiceResult.value = await api(
        `/api/v1/billing/bills/${invoice.billId}/invoice-requests`,
        'POST',
        { invoiceTitle: invoice.invoiceTitle, taxNumber: invoice.taxNumber || undefined },
      )
      return invoiceResult.value
    })
  }
  async function traffic() {
    await action(async () => {
      const params = new URLSearchParams({
        from: iso(analytics.from),
        to: iso(analytics.to),
        granularity: analytics.granularity,
      })
      report.value = await api(`/api/v1/analytics/traffic?${params}`)
      return report.value
    })
  }
  onMounted(async () => {
    const now = new Date()
    analytics.from = localInput(new Date(now.getTime() - 24 * 60 * 60 * 1000))
    analytics.to = localInput(now)
    await refreshOverview()
  })

  return {
    navigation,
    page,
    currentPage,
    types,
    label,
    feeNames,
    services,
    busy,
    spaces,
    chargers,
    session,
    bill,
    report,
    reservation,
    monthly,
    monthlyLedger,
    charging,
    invoiceResult,
    exitResult,
    dashboardReport,
    dashboardError,
    resourcesReady,
    resourceError,
    updatedAt,
    metrics,
    occupancy,
    lastAction,
    noticeType,
    connectedCount,
    settlementStep,
    typeIcon,
    entry,
    search,
    exit,
    reserve,
    pass,
    charge,
    adminSpace,
    invoice,
    analytics,
    output,
    money,
    dateTime,
    action,
    checkServices,
    loadResources,
    refreshOverview,
    createSpace,
    setSpaceStatus,
    createReservation,
    payReservation,
    cancelReservation,
    createPass,
    refreshPass,
    enter,
    locate,
    findFromOverview,
    startCharge,
    finishCharge,
    requestExit,
    completeExit,
    applyInvoice,
    traffic,
  }
}
