package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    @Setter
    private int lastTradedPrice = 0;

    private boolean checkAndDecreaseIfDealerHasEnoughCreditOrSecurity(Order order){
        if(order.getSide()==Side.BUY){
            if(order.getBroker().hasEnoughCredit(order.getValue())){
                order.getBroker().decreaseCreditBy(order.getValue());
                return true;
            }
            else
                return false;
        }
        else
            return order.getShareholder().hasEnoughPositionsOn(order.getSecurity(), order.getQuantity());
    }


    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        Order order;
        if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else if(enterOrderRq.getPeakSize() != 0)
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), OrderStatus.NEW, enterOrderRq.getMinimumExecutionQuantity());
        else {
            order = new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getStopPrice());
            if(!order.canBeActive(lastTradedPrice)) {
                if(checkAndDecreaseIfDealerHasEnoughCreditOrSecurity(order)){
                    orderBook.enqueueInInactiveQueue(order);
                    return MatchResult.inActiveOrderEnqueued();
                }
                else{
                    return (order.getSide()==Side.BUY) ? MatchResult.notEnoughCredit() : MatchResult.notEnoughPositions();
                }
            }
        }
        return matcher.execute(order);

    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findOrderInAllQueues(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());

        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderInBothQueues(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order;
        if(updateOrderRq.getStopPrice()>0)
            order = orderBook.findByOrderIdInInactiveQueue(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        else
            order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.getMinimumExpectedQuantity() != updateOrderRq.getMinimumExecutionQuantity()){
            return MatchResult.cantUpdateMinQuantity();
        }


        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority && updateOrderRq.getStopPrice()==0) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        }
        else
            order.markAsUpdating();

        if(updateOrderRq.getStopPrice()>0){
            orderBook.removeByOrderIdInactiveQueue(updateOrderRq.getSide(),updateOrderRq.getOrderId());
            if(!order.canBeActive(lastTradedPrice)){
                if (updateOrderRq.getSide() == Side.BUY) {
                    order.getBroker().decreaseCreditBy(order.getValue());
                }
                orderBook.enqueueInInactiveQueue(order);
                return MatchResult.inActiveOrderEnqueued();
            }
        }
        else
            orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

//    public Order findOrderToBeActive(){
//        return orderBook.findOrderToBeActive(lastTradedPrice);
//    }
}
