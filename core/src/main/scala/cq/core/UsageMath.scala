package cq.core

import cq.api.*

object UsageMath {
  private def check(value: Boolean, message: String): Unit = LedgerPolicy.invalid(value, message)

  def zeroCounter: Counter = Counter(Some(0L), Measurement.Observed)
  def missingCounter: Counter = Counter(None, Measurement.Missing)
  def zeroCounts: TokenCounts = TokenCounts(zeroCounter, zeroCounter, zeroCounter, zeroCounter, zeroCounter)
  def missingCounts: TokenCounts = TokenCounts(missingCounter, missingCounter, missingCounter, missingCounter, missingCounter)
  def unknownMoney: Money = Money(None, None, CostBasis.Unknown, None)
  def zeroMetric: MetricTotal = MetricTotal(0, 0, 0)
  def zeroTotals: UsageTotals = UsageTotals(zeroMetric, zeroMetric, zeroMetric, zeroMetric, zeroMetric, zeroMetric, Nil, 0)
  def emptyProjection: MeterProjection = MeterProjection(totals(missingCounts, unknownMoney), None, 1, UsageCompleteness.Unavailable, List("No usage observation received"))

  def validate(counter: Counter): Unit = {
    val known = counter.measurement == Measurement.Observed || counter.measurement == Measurement.Estimated
    check(counter.value.isDefined == known, "A measured/estimated counter requires a value; missing/unsupported counters must omit it")
    check(counter.value.forall(_ >= 0), "Token counts must be nonnegative")
  }

  def validate(counts: TokenCounts): Unit = List(counts.input, counts.output, counts.cacheRead, counts.cacheWrite, counts.reasoning).foreach(validate)

  def amount(value: DecimalAmount): BigDecimal = {
    check(value.value.length <= 64 && value.value.matches("[0-9]+(?:\\.[0-9]+)?"), "Money requires a nonnegative decimal string")
    val parsed = BigDecimal(value.value)
    check(parsed.scale <= 18, "Monetary precision exceeds 18 decimal places")
    parsed
  }

  def decimal(value: BigDecimal): DecimalAmount = DecimalAmount(value.bigDecimal.stripTrailingZeros.toPlainString)

  def validate(money: Money): Unit = {
    if (money.basis == CostBasis.Unknown) check(money.amount.isEmpty && money.currency.isEmpty && money.pricingVersion.isEmpty, "Unknown cost cannot declare an amount, currency or price basis")
    else {
      check(money.amount.nonEmpty && money.currency.exists(_.matches("[A-Z]{3}")), "Known cost requires an amount and ISO-style currency code")
      money.amount.foreach(amount)
      if (money.basis == CostBasis.PriceTable) check(money.pricingVersion.exists(_.trim.nonEmpty), "Price-table estimates require a pricing version")
    }
  }

  private def add(left: Counter, right: Counter): Counter = (left.value, right.value) match {
    case (Some(a), Some(b)) => Counter(Some(Math.addExact(a, b)), if (left.measurement == Measurement.Estimated || right.measurement == Measurement.Estimated) Measurement.Estimated else Measurement.Observed)
    case _ => missingCounter
  }

  private def subtract(value: Counter, baseline: Counter): Counter = (value.value, baseline.value) match {
    case (Some(a), Some(b)) =>
      check(a >= b, "Cumulative counter fell below its frozen baseline; a reset requires a new meter")
      Counter(Some(a - b), if (value.measurement == Measurement.Estimated || baseline.measurement == Measurement.Estimated) Measurement.Estimated else Measurement.Observed)
    case _ => missingCounter
  }

  def normalize(observation: UsageObservation): TokenCounts = {
    validate(observation.counters)
    validate(observation.cost)
    val raw = observation.counters
    val input = if (observation.inputIncludesCache) raw.input else add(add(raw.input, raw.cacheRead), raw.cacheWrite)
    val output = if (observation.outputIncludesReasoning) raw.output else add(raw.output, raw.reasoning)
    for { i <- input.value; read <- raw.cacheRead.value; write <- raw.cacheWrite.value }
      check(BigInt(read) + BigInt(write) <= BigInt(i), "Cache counts exceed inclusive input")
    for { o <- output.value; reasoning <- raw.reasoning.value }
      check(reasoning <= o, "Reasoning exceeds inclusive output")
    TokenCounts(input, output, raw.cacheRead, raw.cacheWrite, raw.reasoning)
  }

  def since(value: TokenCounts, baseline: TokenCounts): TokenCounts = TokenCounts(
    subtract(value.input, baseline.input), subtract(value.output, baseline.output),
    subtract(value.cacheRead, baseline.cacheRead), subtract(value.cacheWrite, baseline.cacheWrite), subtract(value.reasoning, baseline.reasoning),
  )

  def since(value: Money, baseline: Money): Money = {
    if (value.basis == CostBasis.Unknown || baseline.basis == CostBasis.Unknown) unknownMoney
    else {
      check(value.currency == baseline.currency && value.basis == baseline.basis && value.pricingVersion == baseline.pricingVersion, "Cumulative monetary basis changed; a new meter is required")
      val difference = amount(value.amount.get) - amount(baseline.amount.get)
      check(difference >= 0, "Cumulative cost fell below its frozen baseline")
      value.copy(amount = Some(decimal(difference)))
    }
  }

  def monotonic(previous: TokenCounts, next: TokenCounts): Unit = {
    List((previous.input, next.input), (previous.output, next.output), (previous.cacheRead, next.cacheRead), (previous.cacheWrite, next.cacheWrite), (previous.reasoning, next.reasoning)).foreach {
      case (a, b) => for { old <- a.value; current <- b.value } check(current >= old, "Cumulative counter decreased without an explicit correction; a reset requires a new meter")
    }
  }

  def monotonic(previous: Money, next: Money): Unit = {
    for { old <- previous.amount; current <- next.amount } {
      check(previous.currency == next.currency && previous.basis == next.basis && previous.pricingVersion == next.pricingVersion, "Cumulative monetary basis changed; a new meter is required")
      check(amount(current) >= amount(old), "Cumulative cost decreased without an explicit correction; a reset requires a new meter")
    }
  }

  private def metric(value: Counter): MetricTotal = MetricTotal(value.value.getOrElse(0L), if (value.value.isEmpty) 1 else 0, if (value.measurement == Measurement.Estimated) 1 else 0)

  def totals(counts: TokenCounts, money: Money): UsageTotals = {
    val input = metric(counts.input)
    val output = metric(counts.output)
    val combined = MetricTotal(Math.addExact(input.known, output.known), if (input.unknown + output.unknown > 0) 1 else 0, if (input.estimated + output.estimated > 0) 1 else 0)
    val costs = money.amount.toList.map(value => CostTotal(value, money.currency.get, money.basis, money.pricingVersion, 1))
    UsageTotals(input, output, metric(counts.cacheRead), metric(counts.cacheWrite), metric(counts.reasoning), combined, costs, if (money.amount.isEmpty) 1 else 0)
  }

  def combine(left: UsageTotals, right: UsageTotals, sign: Long): UsageTotals = {
    require(sign == 1 || sign == -1, "Usage total combination sign")
    def number(a: Long, b: Long): Long = {
      val result = Math.addExact(a, Math.multiplyExact(b, sign))
      require(result >= 0, "Usage projection underflow")
      result
    }
    def metric(a: MetricTotal, b: MetricTotal): MetricTotal = MetricTotal(number(a.known, b.known), number(a.unknown, b.unknown), number(a.estimated, b.estimated))
    val keys = (left.costs ++ right.costs).map(c => (c.currency, c.basis, c.pricingVersion)).distinct
    val costs = keys.flatMap { key =>
      val a = left.costs.find(c => (c.currency, c.basis, c.pricingVersion) == key)
      val b = right.costs.find(c => (c.currency, c.basis, c.pricingVersion) == key)
      val count = number(a.fold(0L)(_.measurements), b.fold(0L)(_.measurements))
      val value = a.fold(BigDecimal(0))(c => amount(c.amount)) + sign * b.fold(BigDecimal(0))(c => amount(c.amount))
      require(value >= 0 && (count != 0 || value == 0), "Usage cost projection underflow")
      if (count == 0) Nil else List(CostTotal(decimal(value), key._1, key._2, key._3, count))
    }.sortBy(c => (c.currency, c.basis.toString, c.pricingVersion.getOrElse("")))
    UsageTotals(metric(left.input, right.input), metric(left.output, right.output), metric(left.cacheRead, right.cacheRead), metric(left.cacheWrite, right.cacheWrite), metric(left.reasoning, right.reasoning), metric(left.total, right.total), costs, number(left.unknownCosts, right.unknownCosts))
  }
}
