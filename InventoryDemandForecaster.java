import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * InventoryDemandForecaster (single-file)
 *
 * - Uses only arrays and java.util.Arrays helpers
 * - Maintains parallel arrays for product data
 * - Tracks a circular sales buffer (days) per product: salesHistory[productIndex][dayIndex]
 * - Forecasts demand using moving averages (7/14/30)
 * - Produces a reorder list and a simple profit estimate
 *
 * Author: Sarthak V. Fatale
 */
public class InventoryDemandForecaster {

    // --- Configuration ---
    private static final int HISTORY_DAYS = 30; // keep last 30 days of sales
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // --- Product data (parallel arrays) ---
    private String[] productNames = new String[0];
    private int[] currentStock = new int[0];
    private double[] costPrice = new double[0];
    private double[] sellingPrice = new double[0];

    // salesHistory[productIndex][dayIndex] = quantity sold on that dayIndex
    // dayIndex is circular; dayIndexCurrent points to "today" column
    private int[][] salesHistory = new int[0][];
    private int dayIndexCurrent = 0; // points to column representing 'today' in salesHistory
    private LocalDate historyStartDate = LocalDate.now().minusDays(HISTORY_DAYS - 1); // earliest column's date

    public InventoryDemandForecaster() {
        // initially no products
        dayIndexCurrent = 0;
        historyStartDate = LocalDate.now().minusDays(HISTORY_DAYS - 1);
    }

    // -------------------------
    // Product management
    // -------------------------
    public void addProduct(String name, int initialStock, double cost, double price) {
        // prevent duplicates (case-insensitive)
        if (findProductIndex(name) != -1) {
            System.out.println("Product already exists: " + name);
            return;
        }

        int newSize = productNames.length + 1;
        productNames = Arrays.copyOf(productNames, newSize);
        currentStock = Arrays.copyOf(currentStock, newSize);
        costPrice = Arrays.copyOf(costPrice, newSize);
        sellingPrice = Arrays.copyOf(sellingPrice, newSize);

        int newIndex = newSize - 1;
        productNames[newIndex] = name;
        currentStock[newIndex] = initialStock;
        costPrice[newIndex] = cost;
        sellingPrice[newIndex] = price;

        // expand salesHistory: create new array with one more row
        int[][] newHistory = new int[newSize][];
        for (int i = 0; i < newSize - 1; i++) {
            newHistory[i] = Arrays.copyOf(salesHistory[i], HISTORY_DAYS);
        }
        // new product row initialized to zeros
        newHistory[newIndex] = new int[HISTORY_DAYS];
        salesHistory = newHistory;

        System.out.println("Added product: " + name + " (stock=" + initialStock + ")");
    }

    public boolean updateStock(String name, int newStock) {
        int idx = findProductIndex(name);
        if (idx == -1) return false;
        currentStock[idx] = newStock;
        return true;
    }

    public boolean updatePrices(String name, double newCost, double newPrice) {
        int idx = findProductIndex(name);
        if (idx == -1) return false;
        costPrice[idx] = newCost;
        sellingPrice[idx] = newPrice;
        return true;
    }

    // find product index by name (case-insensitive); linear search (arrays only)
    public int findProductIndex(String name) {
        for (int i = 0; i < productNames.length; i++) {
            if (productNames[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    // -------------------------
    // Sales recording & day simulation
    // -------------------------

    /**
     * Record sale for "today" for a product
     *
     * Only records sales for the current dayIndex (today). For simplicity this demo assumes
     * real-time recording and uses advanceDay() to simulate passage of days.
     */
    public boolean recordSaleToday(String productName, int qty) {
        int idx = findProductIndex(productName);
        if (idx == -1) {
            System.out.println("Product not found: " + productName);
            return false;
        }
        if (qty <= 0) {
            System.out.println("Quantity must be > 0");
            return false;
        }

        // reduce stock (allow negative to represent backorders; optional policy)
        currentStock[idx] -= qty;

        // increment salesHistory at [idx][dayIndexCurrent]
        salesHistory[idx][dayIndexCurrent] += qty;

        System.out.printf("Recorded sale: %s x%d on %s. New stock=%d%n",
                productName, qty, todayString(), currentStock[idx]);

        return true;
    }

    /**
     * Advance simulation by one day:
     * - Moves dayIndexCurrent forward (circular)
     * - Clears new day's columns (sets to zero)
     * - Advances historyStartDate by +1
     */
    public void advanceDay() {
        dayIndexCurrent = (dayIndexCurrent + 1) % HISTORY_DAYS;
        // clear each product's new "today" column (which was the oldest day before moving)
        for (int i = 0; i < salesHistory.length; i++) {
            salesHistory[i][dayIndexCurrent] = 0;
        }
        historyStartDate = historyStartDate.plusDays(1);
        System.out.println("Advanced day to: " + todayString());
    }

    // returns LocalDate represented by column index offset
    private LocalDate dateAtColumn(int columnIndex) {
        // columnIndex is absolute column in circular buffer; compute offset from historyStartDate
        // The mapping: historyStartDate corresponds to column (dayIndexCurrent + 1) % HISTORY_DAYS
        // But simpler approach: compute day offset for a column relative to historyStartDate.
        // We'll compute chronological order: columns chronological from historyStartDate to today:
        // chronoCol 0 -> historyStartDate, chronoCol HISTORY_DAYS-1 -> today
        int chronoCol = ( (columnIndex - (dayIndexCurrent + 1) ) % HISTORY_DAYS + HISTORY_DAYS) % HISTORY_DAYS;
        // above is tricky; easier: reconstruct date by computing offset of column relative to dayIndexCurrent:
        // Let's compute the number of days from that column to today:
        int daysFromColumnToToday;
        if (columnIndex <= dayIndexCurrent) {
            daysFromColumnToToday = dayIndexCurrent - columnIndex;
        } else {
            daysFromColumnToToday = (HISTORY_DAYS - columnIndex) + dayIndexCurrent;
        }
        LocalDate columnDate = LocalDate.now().minusDays(daysFromColumnToToday);
        return columnDate;
    }

    private String todayString() {
        return LocalDate.now().format(DT_FMT);
    }

    // -------------------------
    // Forecasting
    // -------------------------

    /**
     * Sum last k days of sales for product idx (k <= HISTORY_DAYS)
     * Uses circular indexing starting from dayIndexCurrent as "today".
     */
    private int sumLastKDays(int productIdx, int k) {
        if (productIdx < 0 || productIdx >= productNames.length) return 0;
        if (k <= 0) return 0;
        if (k > HISTORY_DAYS) k = HISTORY_DAYS;

        int sum = 0;
        // iterate backwards from today (dayIndexCurrent) for k days
        int col = dayIndexCurrent;
        for (int i = 0; i < k; i++) {
            sum += salesHistory[productIdx][col];
            col = (col - 1 + HISTORY_DAYS) % HISTORY_DAYS;
        }
        return sum;
    }

    /**
     * Forecast demand for next day based on moving average of last 'window' days.
     * Returns integer predicted units (rounded down).
     */
    public int forecastDailyByWindow(int productIdx, int windowDays) {
        if (productIdx < 0 || productIdx >= productNames.length) return 0;
        if (windowDays <= 0) return 0;
        int sum = sumLastKDays(productIdx, Math.min(windowDays, HISTORY_DAYS));
        return sum / Math.min(windowDays, HISTORY_DAYS); // average per day
    }

    /**
     * Forecast for the next D days by scaling daily forecast.
     * Returns predicted total units to be sold in next daysAhead days.
     */
    public int forecastNextDaysTotal(int productIdx, int windowDays, int daysAhead) {
        int daily = forecastDailyByWindow(productIdx, windowDays);
        return daily * daysAhead;
    }

    // -------------------------
    // Reorder & profit estimation
    // -------------------------

    /**
     * Generate reorder list: products where forecasted next days demand exceeds current stock.
     * Returns parallel arrays: names[], suggestedQty[] (recommended to reorder)
     *
     * windowDays: smoothing window for forecast (7/14/30)
     * daysAhead: planning horizon (e.g., reorder to cover next 14 days)
     */
    public ReorderList generateReorderList(int windowDays, int daysAhead) {
        String[] names = new String[0];
        int[] suggested = new int[0];
        double[] expectedCost = new double[0];

        for (int i = 0; i < productNames.length; i++) {
            int predicted = forecastNextDaysTotal(i, windowDays, daysAhead);
            if (predicted > currentStock[i]) {
                int need = predicted - currentStock[i];
                names = Arrays.copyOf(names, names.length + 1);
                suggested = Arrays.copyOf(suggested, suggested.length + 1);
                expectedCost = Arrays.copyOf(expectedCost, expectedCost.length + 1);

                int p = names.length - 1;
                names[p] = productNames[i];
                suggested[p] = need;
                expectedCost[p] = need * costPrice[i];
            }
        }
        return new ReorderList(names, suggested, expectedCost);
    }

    /**
     * Estimate profit if predicted demand materializes for next daysAhead days.
     * Returns total expected profit (revenue - cost) across all products.
     */
    public double estimateProfit(int windowDays, int daysAhead) {
        double profit = 0.0;
        for (int i = 0; i < productNames.length; i++) {
            int predicted = forecastNextDaysTotal(i, windowDays, daysAhead);
            // if predicted exceeds stock, treat only stock as sellable this period; alternate policies possible
            int sellable = Math.min(predicted, Math.max(currentStock[i], 0)); // don't count negative stock beyond 0
            double revenue = sellable * sellingPrice[i];
            double c = sellable * costPrice[i];
            profit += (revenue - c);
        }
        return profit;
    }

    // -------------------------
    // Reporting & utilities
    // -------------------------
    public void printInventory() {
        System.out.println("Inventory snapshot:");
        System.out.printf("%-20s %-8s %-10s %-10s %-10s%n", "Product", "Stock", "Cost", "Price", "7dAvg");
        for (int i = 0; i < productNames.length; i++) {
            int avg7 = forecastDailyByWindow(i, 7);
            System.out.printf("%-20s %-8d %-10.2f %-10.2f %-10d%n",
                    productNames[i], currentStock[i], costPrice[i], sellingPrice[i], avg7);
        }
    }

    // Print sales history of a product (chronological)
    public void printSalesHistory(String productName) {
        int idx = findProductIndex(productName);
        if (idx == -1) {
            System.out.println("Product not found: " + productName);
            return;
        }
        System.out.println("Sales history for " + productName + " (chronological oldest->today):");
        // print dates and quantities
        for (int offset = HISTORY_DAYS - 1; offset >= 0; offset--) {
            int col = (dayIndexCurrent - offset + HISTORY_DAYS) % HISTORY_DAYS;
            LocalDate d = LocalDate.now().minusDays(offset);
            System.out.printf("%s : %d%n", d.format(DT_FMT), salesHistory[idx][col]);
        }
    }

    // simple sorted ranking by predicted demand (descending)
    public void printTopDemandedProducts(int topK, int windowDays) {
        if (productNames.length == 0) {
            System.out.println("No products.");
            return;
        }
        // create index array and predicted demand array
        int n = productNames.length;
        int[] idx = new int[n];
        int[] demand = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            demand[i] = forecastDailyByWindow(i, windowDays);
        }
        // sort idx by demand descending using simple selection sort or custom comparator simulation (arrays only)
        // we'll implement a simple bubble sort on idx using demand compare (n small typically)
        for (int a = 0; a < n - 1; a++) {
            for (int b = 0; b < n - 1 - a; b++) {
                if (demand[idx[b]] < demand[idx[b + 1]]) {
                    int tmp = idx[b];
                    idx[b] = idx[b + 1];
                    idx[b + 1] = tmp;
                }
            }
        }

        System.out.println("Top demanded products (by daily avg over windowDays=" + windowDays + "):");
        for (int i = 0; i < Math.min(topK, n); i++) {
            int j = idx[i];
            System.out.printf("%d) %s - forecast/daily=%d, stock=%d%n", i + 1, productNames[j], demand[j], currentStock[j]);
        }
    }

    // small helper to print reorder list
    public void printReorderList(ReorderList rl) {
        if (rl.names.length == 0) {
            System.out.println("No items to reorder.");
            return;
        }
        System.out.println("Reorder recommendations:");
        System.out.printf("%-20s %-10s %-12s%n", "Product", "Qty", "Est. Cost");
        for (int i = 0; i < rl.names.length; i++) {
            System.out.printf("%-20s %-10d %-12.2f%n", rl.names[i], rl.quantities[i], rl.estCost[i]);
        }
    }

    // -------------------------
    // Helper data class for reorder list (simple holder)
    // -------------------------
    public static class ReorderList {
        public final String[] names;
        public final int[] quantities;
        public final double[] estCost;

        public ReorderList(String[] names, int[] quantities, double[] estCost) {
            this.names = names;
            this.quantities = quantities;
            this.estCost = estCost;
        }
    }

    // -------------------------
    // Demo main (sample dataset + simulated days)
    // -------------------------
    public static void main(String[] args) throws InterruptedException {
        InventoryDemandForecaster sys = new InventoryDemandForecaster();

        // Add sample products
        sys.addProduct("Widget A", 120, 2.50, 5.00);
        sys.addProduct("Gadget B", 30, 8.00, 12.50);
        sys.addProduct("Coffee Beans", 50, 6.00, 10.00);
        sys.addProduct("Notebook", 200, 0.70, 1.50);

        // Simulate sales over several days
        // We'll record some sales for "today", then advance day and repeat to build history.
        // Day 0:
        sys.recordSaleToday("Widget A", 12);
        sys.recordSaleToday("Gadget B", 3);
        sys.recordSaleToday("Coffee Beans", 5);
        sys.recordSaleToday("Notebook", 9);

        // Advance 1 day and record
        sys.advanceDay();
        sys.recordSaleToday("Widget A", 10);
        sys.recordSaleToday("Gadget B", 4);
        sys.recordSaleToday("Coffee Beans", 8);
        sys.recordSaleToday("Notebook", 7);

        // Advance and record some heavier Widget A days
        for (int i = 0; i < 5; i++) {
            sys.advanceDay();
            sys.recordSaleToday("Widget A", 15 + i); // trend up a bit
            sys.recordSaleToday("Gadget B", 2 + (i % 2));
            sys.recordSaleToday("Coffee Beans", 3 + (i % 3));
            sys.recordSaleToday("Notebook", 5 + (i % 4));
        }

        // Advance a bunch to populate 14-day window
        for (int i = 0; i < 10; i++) {
            sys.advanceDay();
            // small random-ish sales pattern
            sys.recordSaleToday("Widget A", 7 + (i % 5));
            sys.recordSaleToday("Gadget B", 1 + (i % 3));
            sys.recordSaleToday("Coffee Beans", 2 + (i % 4));
            sys.recordSaleToday("Notebook", 3 + (i % 6));
        }

        // Print inventory and stats
        sys.printInventory();
        System.out.println();

        // Print top demand by 7-day window
        sys.printTopDemandedProducts(5, 7);
        System.out.println();

        // Forecast next 14 days and generate reorder recommendations
        int window = 7;
        int horizon = 14;
        ReorderList rl = sys.generateReorderList(window, horizon);
        sys.printReorderList(rl);
        System.out.println();

        // Profit estimate if forecast realized for next 14 days
        double profit = sys.estimateProfit(window, horizon);
        System.out.printf("Estimated profit over next %d days (sellable portion only): %.2f%n", horizon, profit);
        System.out.println();

        // Print sales history for a product
        sys.printSalesHistory("Widget A");
        System.out.println();

        // Demonstrate updating stock and prices
        sys.updateStock("Gadget B", 60);
        sys.updatePrices("Coffee Beans", 5.50, 9.50);
        System.out.println("After updates:");
        sys.printInventory();

        // final reorder with updated stock
        ReorderList rl2 = sys.generateReorderList(7, 14);
        sys.printReorderList(rl2);

        System.out.println("\nDemo complete.");
    }
}