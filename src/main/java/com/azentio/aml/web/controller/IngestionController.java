package com.azentio.aml.web.controller;

import com.azentio.aml.domain.enums.IngestionEntityType;
import com.azentio.aml.domain.enums.IngestionSource;
import com.azentio.aml.service.ingestion.AccountPayload;
import com.azentio.aml.service.ingestion.CsvIngestionService;
import com.azentio.aml.service.ingestion.CustomerPayload;
import com.azentio.aml.service.ingestion.IngestionHistoryService;
import com.azentio.aml.service.ingestion.IngestionResult;
import com.azentio.aml.service.ingestion.MasterDataIngestionService;
import com.azentio.aml.service.ingestion.TransactionIngestionService;
import com.azentio.aml.service.ingestion.TransactionPayload;
import com.azentio.aml.web.dto.IngestionBatchView;
import com.azentio.aml.web.dto.IngestionErrorView;
import com.azentio.aml.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Data ingestion: bulk CSV upload, JSON batch, and the single-transaction real-time endpoint.
 *
 * <p>Role-restricted to {@code ADMIN} and {@code COMPLIANCE_OFFICER} by the security filter chain -
 * loading data changes what the bank believes about its customers, which is an operational
 * capability rather than an analyst one.
 *
 * <h2>Status codes</h2>
 *
 * A partially rejected batch returns {@code 207 Multi-Status} rather than {@code 200}: the good
 * records were stored and the bad ones were not, and a caller that only inspects the status code
 * must not be led to believe the whole file loaded. A batch in which nothing survived returns
 * {@code 422}. Either way the body lists every rejected record with its reason.
 *
 * <h2>Load order</h2>
 *
 * Customers, then accounts, then transactions. Referential integrity is enforced rather than
 * assumed, so loading out of order rejects the dependent rows with a precise reason instead of
 * silently producing orphans.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@Validated
@Tag(name = "Ingestion", description = "Bulk CSV, JSON batch and real-time transaction ingestion")
public class IngestionController {

    private final CsvIngestionService csvIngestionService;
    private final MasterDataIngestionService masterDataIngestionService;
    private final TransactionIngestionService transactionIngestionService;
    private final IngestionHistoryService historyService;

    public IngestionController(
            CsvIngestionService csvIngestionService,
            MasterDataIngestionService masterDataIngestionService,
            TransactionIngestionService transactionIngestionService,
            IngestionHistoryService historyService) {
        this.csvIngestionService = csvIngestionService;
        this.masterDataIngestionService = masterDataIngestionService;
        this.transactionIngestionService = transactionIngestionService;
        this.historyService = historyService;
    }

    // ------------------------------------------------------------------
    // CSV upload
    // ------------------------------------------------------------------

    @PostMapping(value = "/customers/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Bulk load customers from CSV",
            description =
                    "Columns are matched by header name, so optional columns may be omitted or"
                            + " reordered. A customer already on file is refreshed in place.")
    public ResponseEntity<IngestionResult> customersCsv(
            @Parameter(description = "CSV file with a header row") @RequestPart("file")
                    MultipartFile file) {
        return respond(csvIngestionService.ingestCustomers(file));
    }

    @PostMapping(value = "/accounts/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Bulk load accounts from CSV",
            description = "Every row must reference a customer already on file.")
    public ResponseEntity<IngestionResult> accountsCsv(
            @RequestPart("file") MultipartFile file) {
        return respond(csvIngestionService.ingestAccounts(file));
    }

    @PostMapping(value = "/transactions/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Bulk load transactions from CSV",
            description =
                    "Amounts are normalised to the base currency and the detection engine is run"
                            + " over the loaded window, so alerts exist by the time the response"
                            + " returns. Re-uploading the same file is safe: repeated transaction"
                            + " ids are counted as duplicates and skipped.")
    public ResponseEntity<IngestionResult> transactionsCsv(
            @RequestPart("file") MultipartFile file) {
        return respond(csvIngestionService.ingestTransactions(file));
    }

    // ------------------------------------------------------------------
    // JSON batch
    // ------------------------------------------------------------------

    @PostMapping("/customers")
    @Operation(summary = "Load a batch of customers as JSON")
    public ResponseEntity<IngestionResult> customers(
            @RequestBody @NotEmpty @Valid List<CustomerPayload> payloads) {
        return respond(
                masterDataIngestionService.ingestCustomers(
                        payloads, IngestionSource.REST_BATCH, null));
    }

    @PostMapping("/accounts")
    @Operation(summary = "Load a batch of accounts as JSON")
    public ResponseEntity<IngestionResult> accounts(
            @RequestBody @NotEmpty @Valid List<AccountPayload> payloads) {
        return respond(
                masterDataIngestionService.ingestAccounts(
                        payloads, IngestionSource.REST_BATCH, null));
    }

    @PostMapping("/transactions")
    @Operation(summary = "Load a batch of transactions as JSON")
    public ResponseEntity<IngestionResult> transactions(
            @RequestBody @NotEmpty @Valid List<TransactionPayload> payloads) {
        return respond(
                transactionIngestionService.ingest(payloads, IngestionSource.REST_BATCH, null));
    }

    // ------------------------------------------------------------------
    // Real-time single transaction
    // ------------------------------------------------------------------

    @PostMapping("/transactions/stream")
    @Operation(
            summary = "Ingest and screen one transaction in real time",
            description =
                    "The synchronous alternative to the Kafka consumer. Detection runs inline, so"
                            + " the response reports whether the transaction raised an alert.")
    public ResponseEntity<IngestionResult> streamOne(
            @Valid @RequestBody TransactionPayload payload) {
        return respond(
                transactionIngestionService.ingestOne(payload, IngestionSource.REST_SINGLE));
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @GetMapping("/batches")
    @Operation(summary = "Ingestion history, most recent first")
    public PageResponse<IngestionBatchView> batches(
            @RequestParam(required = false) IngestionEntityType entityType,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        return PageResponse.of(
                historyService.history(entityType, PageRequest.of(page, size)),
                IngestionBatchView::from);
    }

    @GetMapping("/batches/{batchReference}")
    @Operation(summary = "A single ingestion batch and its counters")
    public IngestionBatchView batch(@PathVariable String batchReference) {
        return IngestionBatchView.from(historyService.require(batchReference));
    }

    @GetMapping("/batches/{batchReference}/errors")
    @Operation(
            summary = "Rows a batch rejected",
            description = "In file order, with the raw record retained for correction.")
    public PageResponse<IngestionErrorView> batchErrors(
            @PathVariable String batchReference,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return PageResponse.of(
                historyService.errorsOf(batchReference, PageRequest.of(page, size)),
                IngestionErrorView::from);
    }

    /**
     * Maps the batch outcome onto a status code that is honest about partial success.
     *
     * <p>A caller that stores half a file and reports {@code 200 OK} is the reason reconciliation
     * breaks silently, so the three outcomes are given three distinct codes.
     */
    private ResponseEntity<IngestionResult> respond(IngestionResult result) {
        if (!result.hasErrors()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        }
        HttpStatus status =
                result.succeeded() == 0 ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.MULTI_STATUS;
        return ResponseEntity.status(status).body(result);
    }
}
