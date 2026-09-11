package com.techverito.banking.service;

import com.techverito.banking.dto.OrderRequest;
import com.techverito.banking.dto.OrderResponse;
import com.techverito.banking.entity.Order;
import com.techverito.banking.entity.OrderLineItem;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderResponse create(OrderRequest req) {
        List<OrderLineItem> lineItems = req.lineItems().stream()
                .map(li -> OrderLineItem.builder()
                        .amount(li.amount())
                        .build())
                .toList();

        BigDecimal itemsTotal = lineItems.stream()
                .map(li -> li.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discountAmount = (req.discountAmount() != null)
                ? req.discountAmount()
                : BigDecimal.ZERO;

        if (discountAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("discountAmount must be >= 0");
        }

        if (discountAmount.compareTo(itemsTotal) > 0) {
            throw new IllegalArgumentException("discountAmount must be <= itemsTotal");
        }

        BigDecimal total = itemsTotal.subtract(discountAmount);

        Order order = Order.builder()
                .lineItems(lineItems)
                .discountAmount(discountAmount)
                .total(total)
                .build();

        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        return OrderResponse.from(findOrThrow(id));
    }

    private Order findOrThrow(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }
}
