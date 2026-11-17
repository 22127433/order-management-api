package com.example.ordermanagement.product;

import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.order.OrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;

    public ProductService(ProductRepository productRepository, OrderItemRepository orderItemRepository) {
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(request.name(), request.description(), request.price(), request.stockQuantity());
        return ProductResponse.from(productRepository.save(product));
    }

    public ProductResponse getById(Long id) {
        return ProductResponse.from(productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id)));
    }

    public PageResponse<ProductResponse> findAll(int page, int size) {
        return findAll(new ProductSearchQuery(null, null, null, null, page, size, null));
    }

    public PageResponse<ProductResponse> findAll(ProductSearchQuery search) {
        return PageResponse.from(productRepository.findAll(ProductSpecifications.matching(search), search.pageable())
                .map(ProductResponse::from));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = findForUpdate(id);
        product.update(request.name(), request.description(), request.price(), request.stockQuantity());
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Long id) {
        Product product = findForUpdate(id);
        if (orderItemRepository.existsByProductId(id)) {
            throw new ConflictException("Product " + id + " is referenced by an order and cannot be deleted");
        }
        productRepository.delete(product);
    }

    private Product findForUpdate(Long id) {
        return productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }
}
