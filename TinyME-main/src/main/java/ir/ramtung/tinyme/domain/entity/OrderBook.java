package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;
    private final LinkedList<Order> inactiveBuyQueue;
    private final LinkedList<Order> inactiveSellQueue;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
        inactiveBuyQueue = new LinkedList<>();
        inactiveSellQueue = new LinkedList<>();
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.queue();
        it.add(order);
    }



    private LinkedList<Order> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    private LinkedList<Order> getInactiveQueue(Side side){
        return side == Side.BUY ? inactiveBuyQueue : inactiveSellQueue;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        var it = queue.listIterator();
        return deleteById(orderId,it);
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreOrder(Order order) {
        removeByOrderId(order.getSide(), order.getOrderId());
        putBack(order);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public void removeFirst(Side side) {
        getQueue(side).removeFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public void enqueueInInactiveQueue(Order order) {
        var queue = getInactiveQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.inActievOrderQueuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        it.add(order);
    }

    private boolean deleteById(long orderId, Iterator<Order> it){
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public boolean removeByOrderIdInactiveQueue(Side side, long orderId) {
        var it = getInactiveQueue(side).listIterator();
        return deleteById(orderId,it);
    }

    public Order dequeueFromInactiveQueue(Side side, int lastTradedPrice) {
        ListIterator<Order> it = getInactiveQueue(side).listIterator();
        if (it.hasNext()) {
            Order order = it.next();
            if(order.canBeActive(lastTradedPrice)) {
                it.remove();
                return order;
            }
        }
        return null;
    }

    public Order findByOrderIdInInactiveQueue(Side side, long orderId) {
        var inactiveQueue = getInactiveQueue(side);
        for (Order order : inactiveQueue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public Order findOrderInAllQueues(Side side, long orderId){
        Order orderInInActiveQueue = findByOrderIdInInactiveQueue(side, orderId);
        if(orderInInActiveQueue!=null)
            return orderInInActiveQueue;
        return findByOrderId(side, orderId);
    }

    public void removeByOrderInBothQueues(Side side, long orderId){
        removeByOrderId(side, orderId);
        removeByOrderIdInactiveQueue(side, orderId);
    }

}
