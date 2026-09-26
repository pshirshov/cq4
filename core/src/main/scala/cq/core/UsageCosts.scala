package cq.core

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8

final case class MoneyKey(currency: String, basis: CostBasis, pricingVersion: Option[String])
final case class CostProjection(amount: BigDecimal, measurements: Long)

object UsageCosts {
  def adjust(tx: UsageTransaction, meter: MeterKey, money: Money, sign: Long): Unit = {
    require(sign == 1 || sign == -1, "Cost adjustment sign")
    money.amount.foreach { amount =>
      val group = MoneyKey(money.currency.get, money.basis, money.pricingVersion)
      val previous = tx.cost(meter, group).getOrElse(CostProjection(BigDecimal(0), 0))
      val next = CostProjection(UsageMath.addAmount(previous.amount, UsageMath.amount(amount), sign), Math.addExact(previous.measurements, sign))
      require(next.amount >= 0 && next.measurements >= 0 && (next.measurements != 0 || next.amount == 0), "Usage cost projection underflow")
      tx.putCost(meter, group, if (next.measurements == 0) None else Some(next))
    }
  }

  def compare(left: CostGroup, right: CostGroup): Int = {
    val a = List(left.attribution.toString, left.currency, left.basis.toString, left.pricingVersion.getOrElse(""))
    val b = List(right.attribution.toString, right.currency, right.basis.toString, right.pricingVersion.getOrElse(""))
    a.zip(b).iterator.map { case (x, y) => java.util.Arrays.compareUnsigned(x.getBytes(UTF_8), y.getBytes(UTF_8)) }.find(_ != 0).getOrElse(0)
  }
}
