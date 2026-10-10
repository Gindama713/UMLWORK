<script setup>
import AppIcon from '../components/AppIcon.vue'

// This page presents the shared workspace; business actions stay in the composable.
const props = defineProps({ workspace: { type: Object, required: true } })
const {
  label,
  feeNames,
  busy,
  session,
  bill,
  invoiceResult,
  exitResult,
  settlementStep,
  search,
  exit,
  invoice,
  money,
  dateTime,
  locate,
  requestExit,
  completeExit,
  applyInvoice,
} = props.workspace
</script>

<template>
  <div class="checkout-steps" aria-label="结算进度">
    <div
      v-for="(title, index) in ['确认车辆', '核对账单', '支付完成', '车位释放']"
      :key="title"
      :class="{ done: settlementStep > index, current: settlementStep === index }"
    >
      <span
        ><AppIcon v-if="settlementStep > index" name="check" /><template v-else>{{
          index + 1
        }}</template></span
      ><strong>{{ title }}</strong>
    </div>
  </div>
  <div class="checkout-layout">
    <section class="receipt">
      <div class="receipt-brand">
        <AppIcon name="plane" /><span>AERO / PARK<small>停车结算凭据 · 模拟支付</small></span
        ><el-tag v-if="bill" :type="bill.status === 'PAID' ? 'success' : 'warning'">{{
          label(bill.status)
        }}</el-tag>
      </div>
      <template v-if="session"
        ><div class="receipt-vehicle">
          <span class="license-plate">{{ session.plateNumber }}</span
          ><span>{{ session.floor }} · {{ session.zone }}区 · {{ session.spaceNumber }}</span>
        </div>
        <div class="receipt-times">
          <div>
            <span>入场时间</span><strong>{{ dateTime(session.entryTime) }}</strong>
          </div>
          <div>
            <span>结算时间</span><strong>{{ dateTime(exit.exitTime) }}</strong>
          </div>
        </div></template
      >
      <template v-if="bill"
        ><div class="receipt-items">
          <div
            v-for="(item, index) in bill.feeItems"
            :key="index"
            class="receipt-item"
            :class="{ deduction: ['DISCOUNT', 'PREPAID_APPLIED'].includes(item.name) }"
          >
            <div>
              <strong>{{ feeNames[item.name] || item.name }}</strong>
              <p>{{ item.detail }}</p>
            </div>
            <span
              >{{ ['DISCOUNT', 'PREPAID_APPLIED'].includes(item.name) ? '− ' : ''
              }}{{ money(item.amountCents) }}</span
            >
          </div>
        </div>
        <p v-if="bill.prepaidRefundCents" class="refund-note">
          应退预付差额
          {{ money(bill.prepaidRefundCents) }}，由预约服务处理；不计入本次应付。
        </p>
        <div class="receipt-total">
          <span>本次应付<small>人民币 CNY</small></span
          ><strong>{{ money(bill.amountDueCents) }}</strong>
        </div>
        <details class="advanced-options">
          <summary>查看账单与规则版本</summary>
          <p>
            账单 <code>{{ bill.billId }}</code>
          </p>
          <p>
            费率版本 <code>{{ bill.rateVersion }}</code>
          </p>
          <p class="muted">停车费、优惠、预付抵扣、充电及异常费用均由后端生成快照。</p>
        </details>
        <div class="receipt-bottom">THANK YOU & HAVE A GOOD TRIP<AppIcon name="plane" /></div
      ></template>
      <div v-else class="receipt-empty">
        <AppIcon name="ticket" />
        <h3>费用清楚，出场顺畅</h3>
        <p>确认在场车辆后生成账单<br />停车、充电与优惠将在此逐项列明。</p>
      </div>
    </section>
    <div class="checkout-controls">
      <section class="panel">
        <div class="panel-heading">
          <div>
            <span class="section-kicker">CHECKOUT DESK</span>
            <h2>出场工作台</h2>
          </div>
          <AppIcon name="wallet" />
        </div>
        <label class="field-label" for="checkout-plate">查询在场车辆</label>
        <div class="search-row">
          <el-input
            id="checkout-plate"
            v-model="search.plateNumber"
            placeholder="输入待结算车牌"
            @keyup.enter="locate"
          /><el-button :loading="busy" :disabled="!search.plateNumber.trim()" @click="locate"
            >查询</el-button
          >
        </div>
        <div v-if="session" class="selected-vehicle">
          <AppIcon name="car" /><strong>{{ session.plateNumber }}</strong
          ><span>{{ label(session.status) }}</span>
        </div>
        <template v-if="!bill"
          ><label class="stacked-field"
            >出场类型<el-select v-model="exit.exceptionType" aria-label="出场类型"
              ><el-option label="正常出场" value="NONE" /><el-option
                label="丢卡处理"
                value="LOST_CARD" /></el-select></label
          ><label v-if="exit.exceptionType === 'LOST_CARD'" class="stacked-field"
            >异常处理人<el-input v-model="exit.operator" placeholder="请输入处理人"
          /></label>
          <details class="advanced-options">
            <summary>演示时间与停车记录</summary>
            <label class="stacked-field"
              >出场时间（留空使用当前时刻）<input
                v-model="exit.exitTime"
                type="datetime-local"
                step="0.001" /></label
            ><label class="stacked-field"
              >停车记录 ID<el-input
                v-model="exit.sessionId"
                :disabled="!!session"
                placeholder="可使用接口返回的记录 ID"
            /></label>
          </details>
          <el-button
            class="full-button"
            type="primary"
            :loading="busy"
            :disabled="!exit.sessionId"
            @click="requestExit"
            >生成结算账单<AppIcon name="arrow" /></el-button
        ></template>
        <template v-else
          ><div class="payment-summary">
            <span>{{ bill.status === 'PAID' ? '已支付金额' : '待付金额' }}</span
            ><strong>{{ money(bill.amountDueCents) }}</strong>
          </div>
          <el-alert
            v-if="exitResult"
            :title="`停车记录：${label(exitResult.status)}${exitResult.spaceReleased === true ? '，车位已释放' : ''}`"
            :type="exitResult.status === 'CLOSED' ? 'success' : 'warning'"
            :closable="false"
            show-icon
          /><el-button
            class="full-button"
            type="primary"
            :loading="busy"
            :disabled="session?.status === 'CLOSED' || exitResult?.status === 'CLOSED'"
            @click="completeExit('SUCCESS')"
            >{{
              session?.status === 'CLOSED' || exitResult?.status === 'CLOSED'
                ? '出场已完成'
                : bill.status === 'PAID'
                  ? '重试释放并完成出场'
                  : '模拟支付并完成出场'
            }}<AppIcon name="arrow"
          /></el-button>
          <details v-if="bill.status !== 'PAID'" class="advanced-options">
            <summary>支付失败场景演示</summary>
            <p class="muted">模拟支付失败会保留账单，供再次支付。</p>
            <el-button :loading="busy" @click="completeExit('FAILURE')">模拟支付失败</el-button>
          </details></template
        >
        <p class="form-note">先确认支付，再释放车位；释放失败可重试。</p>
      </section>
      <section class="panel invoice-panel">
        <div class="panel-heading">
          <h2>电子发票</h2>
          <span class="small-badge">模拟申请</span>
        </div>
        <p v-if="bill?.status !== 'PAID'" class="muted">账单支付完成后，可申请电子发票。</p>
        <template v-else
          ><label class="stacked-field"
            >发票抬头<el-input
              v-model="invoice.invoiceTitle"
              placeholder="个人姓名或企业名称" /></label
          ><label class="stacked-field"
            >税号（可选）<el-input
              v-model="invoice.taxNumber"
              placeholder="企业纳税人识别号" /></label
          ><el-button
            class="full-button"
            :loading="busy"
            :disabled="!invoice.invoiceTitle.trim()"
            @click="applyInvoice"
            >提交发票申请</el-button
          ></template
        >
        <div v-if="invoiceResult" class="invoice-success">
          <AppIcon name="check" />
          <div>
            <strong>申请已受理（模拟）</strong><code>{{ invoiceResult.invoiceRequestId }}</code>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>
