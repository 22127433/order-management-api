package com.example.ordermanagement.product;

import com.example.ordermanagement.common.BadRequestException;
import com.example.ordermanagement.common.ConflictException;
import com.example.ordermanagement.common.PageResponse;
import com.example.ordermanagement.common.ResourceNotFoundException;
import com.example.ordermanagement.order.OrderItemRepository;
import com.example.ordermanagement.inventory.RestockRequest;
import com.example.ordermanagement.inventory.StockMovement;
import com.example.ordermanagement.inventory.StockMovementQuery;
import com.example.ordermanagement.inventory.StockMovementRepository;
import com.example.ordermanagement.inventory.StockMovementResponse;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockMovementRepository stockMovementRepository;

    public ProductService(ProductRepository productRepository, OrderItemRepository orderItemRepository,
                          StockMovementRepository stockMovementRepository) {
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(request.name(), request.description(), request.price(), request.stockQuantity());
        Product saved = productRepository.save(product);
        stockMovementRepository.save(StockMovement.initial(saved));
        return ProductResponse.from(saved);
    }

    public ProductResponse getById(Long id) {
        return ProductResponse.from(productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id)));
    }

    public PageResponse<ProductResponse> findAll(int page, int size) {
        return findAll(new ProductSearchQuery(null, null, null, null, page, size, null));
    }

    public PageResponse<ProductResponse> findAll(ProductSearchQuery search) {
        return search(search, true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<ProductResponse> findAllForAdmin(ProductSearchQuery search, Boolean active) {
        return search(search, active);
    }

    private PageResponse<ProductResponse> search(ProductSearchQuery search, Boolean active) {
        return PageResponse.from(productRepository.findAll(ProductSpecifications.matching(search, active), search.pageable())
                .map(ProductResponse::from));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse changeAvailability(Long id, ProductAvailabilityRequest request) {
        if (request == null || request.active() == null) {
            throw new BadRequestException("active is required");
        }
        // Share the same product-row lock as ordering, restock, metadata updates, and deletion.
        Product product = findForUpdate(id);
        product.changeAvailability(request.active());
        return ProductResponse.from(product);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = findForUpdate(id);
        if (request.stockQuantity() == null || request.stockQuantity() != product.getStockQuantity()) {
            throw new ConflictException("Product PUT must keep stockQuantity unchanged; use the restock endpoint to add stock");
        }
        product.update(request.name(), request.description(), request.price(), request.stockQuantity());
        return ProductResponse.from(product);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductResponse restock(Long id, RestockRequest request) {
        if (request == null || request.quantity() == null || request.quantity() <= 0
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) {
            throw new BadRequestException("Restock requires positive quantity and a reason of 1 to 500 characters");
        }
        Product product = findForUpdate(id);
        int before = product.getStockQuantity();
        product.restoreStock(request.quantity());
        stockMovementRepository.save(StockMovement.restock(product, request.quantity(), before, request.reason()));
        return ProductResponse.from(product);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<StockMovementResponse> stockMovements(Long id, StockMovementQuery query) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product", id);
        }
        return PageResponse.from(stockMovementRepository.findByProductId(id, query.pageable())
                .map(StockMovementResponse::from));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id) {
        Product product = findForUpdate(id);
        if (orderItemRepository.existsByProductId(id)) {
            throw new ConflictException("Product " + id + " is referenced by an order and cannot be deleted");
        }
        if (stockMovementRepository.existsByProductId(id)) {
            throw new ConflictException("Product " + id + " has inventory history and cannot be deleted");
        }
        productRepository.delete(product);
    }

    private Product findForUpdate(Long id) {
        return productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }
}
