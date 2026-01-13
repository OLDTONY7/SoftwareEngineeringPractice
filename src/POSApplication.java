import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// Domain Entities
class Product {
    String id;
    String name;
    double price;
    int stock;

    public Product(String id, String name, double price, int stock) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }
}

class SaleItem {
    Product product;
    int quantity;
    double subtotal;

    public SaleItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
        this.subtotal = product.price * quantity;
    }
}

// System Controller (Backend)
class POSSystem {
    private Map<String, Product> inventory = new HashMap<>();
    private List<SaleItem> currentCart = new ArrayList<>();
    private final String DB_URL = "jdbc:sqlite:pos.db?busy_timeout=5000";

    public POSSystem() {
        initDatabase();
        loadInventoryFromDB();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private void initDatabase() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            String sqlCreate = "CREATE TABLE IF NOT EXISTS products ("
                    + "id TEXT PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "price REAL, "
                    + "stock INTEGER)";
            stmt.execute(sqlCreate);

            ResultSet rs = stmt.executeQuery("SELECT count(*) FROM products");
            if (rs.next() && rs.getInt(1) == 0) {
                addProductToCatalog("101", "Fresh Milk", 3.50, 20);
                addProductToCatalog("102", "Sourdough Bread", 5.00, 15);
                addProductToCatalog("103", "Apple (Red)", 0.80, 100);
                addProductToCatalog("104", "Cola Can", 1.50, 50);
                addProductToCatalog("105", "Chocolate Bar", 2.00, 30);
            }
        } catch (Exception e) {
            System.err.println("DB Init Error: " + e.getMessage());
        }
    }

    private void loadInventoryFromDB() {
        inventory.clear();
        String sql = "SELECT id, name, price, stock FROM products";
        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Product p = new Product(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getDouble("price"),
                        rs.getInt("stock")
                );
                inventory.put(p.id, p);
            }
        } catch (SQLException e) {
            System.err.println("Load Error: " + e.getMessage());
        }
    }

    private void updateProductStockInDB(String productId, int newStock) {
        String sql = "UPDATE products SET stock = ? WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newStock);
            pstmt.setString(2, productId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Update Stock Error: " + e.getMessage());
        }
    }

    public void addProductToCatalog(String id, String name, double price, int stock) throws Exception {
        if (inventory.containsKey(id)) {
            throw new Exception("Product ID '" + id + "' already exists.");
        }

        String sql = "INSERT INTO products(id, name, price, stock) VALUES(?,?,?,?)";
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, name);
            pstmt.setDouble(3, price);
            pstmt.setInt(4, stock);
            pstmt.executeUpdate();
            Product newProduct = new Product(id, name, price, stock);
            inventory.put(id, newProduct);
        } catch (SQLException e) {
            throw new Exception("Database Error: " + e.getMessage());
        }
    }

    public Product getProduct(String id) {
        return inventory.get(id);
    }

    public List<Product> getAllProducts() {
        return new ArrayList<>(inventory.values());
    }

    public String recordSaleItem(String id, int quantity) throws Exception {
        Product p = getProduct(id);
        if (p == null) {
            throw new Exception("Product ID '" + id + "' not found.\nSuggestion: Please check the item or enter ID manually.");
        }

        int currentCartQty = currentCart.stream()
                .filter(item -> item.product.id.equals(id))
                .mapToInt(item -> item.quantity)
                .sum();

        if ((p.stock - currentCartQty) < quantity) {
            throw new Exception("Error: Insufficient stock. Only " + p.stock + " available.");
        }

        SaleItem item = new SaleItem(p, quantity);
        currentCart.add(item);
        return String.format("Recorded: %s x%d = $%.2f", p.name, quantity, item.subtotal);
    }

    public double getRunningTotal() {
        return currentCart.stream().mapToDouble(i -> i.subtotal).sum();
    }

    public String processPayment(double cashTendered) throws Exception {
        double total = getRunningTotal();
        if (currentCart.isEmpty()) throw new Exception("Cart is empty.");
        if (cashTendered < total) throw new Exception(String.format("Insufficient cash. Need $%.2f", total));

        double change = cashTendered - total;
        for (SaleItem item : currentCart) {
            item.product.stock -= item.quantity;
            updateProductStockInDB(item.product.id, item.product.stock);
        }

        String receipt = generateReceipt(cashTendered, change);
        currentCart.clear();
        return receipt;
    }

    public String processReturn(String id, int quantity) throws Exception {
        Product p = getProduct(id);
        if (p == null) throw new Exception("Product ID '" + id + "' not found.");

        p.stock += quantity;
        updateProductStockInDB(p.id, p.stock);
        double refundAmount = p.price * quantity;
        boolean creditCardSuccess = new Random().nextBoolean();

        StringBuilder msg = new StringBuilder();
        msg.append(String.format("Return Processed: %s x%d\n", p.name, quantity));
        msg.append(String.format("Inventory Updated: +%d\n", quantity));
        msg.append("------------------------------\n");

        if (creditCardSuccess) {
            msg.append(String.format("SUCCESS: Refunded $%.2f to Credit Card.", refundAmount));
        } else {
            msg.append("ALERT: Credit Card Refund DECLINED.\n");
            msg.append(String.format("ACTION: Refund CASH $%.2f", refundAmount));
        }
        return msg.toString();
    }

    private String generateReceipt(double cash, double change) {
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        sb.append("\n==================================\n");
        sb.append("       OFFICIAL RECEIPT\n");
        sb.append("       " + dtf.format(LocalDateTime.now()) + "\n");
        sb.append("==================================\n");
        sb.append(String.format("%-15s %-5s %s\n", "Item", "Qty", "Price"));
        sb.append("----------------------------------\n");

        double total = 0;
        for (SaleItem item : currentCart) {
            sb.append(String.format("%-15s x%-4d $%.2f\n", item.product.name, item.quantity, item.subtotal));
            total += item.subtotal;
        }

        sb.append("----------------------------------\n");
        sb.append(String.format("TOTAL:          $%.2f\n", total));
        sb.append(String.format("CASH PAID:      $%.2f\n", cash));
        sb.append(String.format("CHANGE:         $%.2f\n", change));
        sb.append("==================================\n");
        sb.append("    Thank you for shopping!");
        return sb.toString();
    }
}

// UI Layer (Frontend)
public class POSApplication extends JFrame {

    private POSSystem system;
    private ModernTextField txtId, txtQty, txtCash;
    private JTextArea txtScreen;
    private JTable tblInventory;
    private DefaultTableModel tableModel;
    private JLabel lblTotal;
    private boolean isNewTransaction = true;

    // Color Constants
    private final Color CLR_BG_DARK     = new Color(33, 33, 33);
    private final Color CLR_PANEL       = new Color(48, 48, 48);
    private final Color CLR_TEXT_MAIN   = new Color(240, 240, 240);
    private final Color CLR_TEXT_MUTE   = new Color(170, 170, 170);
    private final Color CLR_ACCENT_BLUE = new Color(58, 150, 221);
    private final Color CLR_ACCENT_GREEN= new Color(76, 175, 80);
    private final Color CLR_ACCENT_RED  = new Color(229, 57, 53);
    private final Color CLR_ACCENT_ORANGE = new Color(255, 152, 0);
    private final Color CLR_INPUT_BG    = new Color(60, 60, 60);

    // Font Constants
    private final Font FONT_HEADER = new Font("Segoe UI", Font.BOLD, 22);
    private final Font FONT_LABEL  = new Font("Segoe UI", Font.PLAIN, 14);
    private final Font FONT_INPUT  = new Font("Segoe UI", Font.PLAIN, 16);
    private final Font FONT_TOTAL  = new Font("Segoe UI", Font.BOLD, 48);

    public POSApplication() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try {
            system = new POSSystem();
            initUI();
            refreshInventoryUI();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initUI() {
        setTitle("Modern POS System (Java + SQLite)");
        setSize(1200, 800);
        setMinimumSize(new Dimension(1000, 700));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        getContentPane().setBackground(CLR_BG_DARK);
        setLayout(new BorderLayout(0, 0));

        JPanel mainContainer = new JPanel(new BorderLayout(20, 20));
        mainContainer.setBackground(CLR_BG_DARK);
        mainContainer.setBorder(new EmptyBorder(20, 20, 20, 20));
        add(mainContainer);

        // Left Panel
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setBackground(CLR_PANEL);
        leftPanel.setBorder(new EmptyBorder(25, 25, 25, 25));

        JLabel title = new JLabel("CASHIER CONTROLS");
        title.setFont(FONT_HEADER);
        title.setForeground(CLR_TEXT_MAIN);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(title);
        leftPanel.add(Box.createVerticalStrut(20));

        leftPanel.add(createLabel("Item ID / Barcode"));
        leftPanel.add(Box.createVerticalStrut(5));
        txtId = new ModernTextField();
        leftPanel.add(txtId);
        leftPanel.add(Box.createVerticalStrut(10));

        leftPanel.add(createLabel("Quantity"));
        leftPanel.add(Box.createVerticalStrut(5));
        txtQty = new ModernTextField();
        txtQty.setText("1");
        leftPanel.add(txtQty);
        leftPanel.add(Box.createVerticalStrut(20));

        leftPanel.add(createSectionLabel("ACTIONS"));
        leftPanel.add(Box.createVerticalStrut(10));

        ModernButton btnScan = new ModernButton("Scan Item", CLR_ACCENT_BLUE);
        btnScan.addActionListener(e -> actionScan());
        leftPanel.add(btnScan);
        leftPanel.add(Box.createVerticalStrut(10));

        ModernButton btnReturn = new ModernButton("Handle Return", CLR_ACCENT_RED);
        btnReturn.addActionListener(e -> actionReturn());
        leftPanel.add(btnReturn);
        leftPanel.add(Box.createVerticalStrut(20));

        leftPanel.add(createSectionLabel("PAYMENT"));
        leftPanel.add(Box.createVerticalStrut(10));
        txtCash = new ModernTextField();
        txtCash.setPlaceholder("Enter Cash Amount ($)");
        leftPanel.add(txtCash);
        leftPanel.add(Box.createVerticalStrut(10));

        ModernButton btnPay = new ModernButton("Process Payment", CLR_ACCENT_GREEN);
        btnPay.addActionListener(e -> actionPay());
        leftPanel.add(btnPay);

        leftPanel.add(Box.createVerticalStrut(20));
        leftPanel.add(createSectionLabel("ADMIN / MANAGE"));
        leftPanel.add(Box.createVerticalStrut(10));
        ModernButton btnAddProduct = new ModernButton("Add New Product", CLR_ACCENT_ORANGE);
        btnAddProduct.addActionListener(e -> actionAddProduct());
        leftPanel.add(btnAddProduct);

        leftPanel.add(Box.createVerticalStrut(20));
        leftPanel.add(createSectionLabel("LIVE INVENTORY (DB)"));
        leftPanel.add(Box.createVerticalStrut(5));

        String[] columnNames = {"ID", "Name", "Price", "Stock"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        tblInventory = new JTable(tableModel);
        tblInventory.setBackground(CLR_INPUT_BG);
        tblInventory.setForeground(new Color(100, 181, 246));
        tblInventory.setGridColor(new Color(80, 80, 80));
        tblInventory.setRowHeight(25);
        tblInventory.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        tblInventory.setShowVerticalLines(false);
        tblInventory.setFillsViewportHeight(true);

        JTableHeader header = tblInventory.getTableHeader();
        header.setBackground(CLR_PANEL);
        header.setForeground(Color.LIGHT_GRAY);
        header.setFont(new Font("Segoe UI", Font.BOLD, 12));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(JLabel.RIGHT);

        tblInventory.getColumnModel().getColumn(0).setPreferredWidth(50);
        tblInventory.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        tblInventory.getColumnModel().getColumn(1).setPreferredWidth(140);
        tblInventory.getColumnModel().getColumn(2).setPreferredWidth(60);
        tblInventory.getColumnModel().getColumn(2).setCellRenderer(rightRenderer);
        tblInventory.getColumnModel().getColumn(3).setPreferredWidth(50);
        tblInventory.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);

        JScrollPane scrollInv = new JScrollPane(tblInventory);
        scrollInv.setBorder(new LineBorder(CLR_PANEL, 1));
        scrollInv.getViewport().setBackground(CLR_INPUT_BG);
        scrollInv.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftPanel.add(scrollInv);

        // Right Panel
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(Color.BLACK);
        rightPanel.setBorder(new LineBorder(new Color(60,60,60), 5));

        txtScreen = new JTextArea();
        txtScreen.setFont(new Font("Monospaced", Font.BOLD, 16));
        txtScreen.setBackground(Color.BLACK);
        txtScreen.setForeground(new Color(0, 255, 100));
        txtScreen.setEditable(false);
        txtScreen.setText("\n  >> SYSTEM INITIALIZED...\n  >> READY FOR TRANSACTION...\n");
        txtScreen.setMargin(new Insets(20, 20, 20, 20));
        txtScreen.setCaretColor(Color.WHITE);

        JScrollPane scrollScreen = new JScrollPane(txtScreen);
        scrollScreen.setBorder(null);
        rightPanel.add(scrollScreen, BorderLayout.CENTER);

        JPanel totalPanel = new JPanel(new BorderLayout());
        totalPanel.setBackground(new Color(20, 20, 20));
        totalPanel.setBorder(new EmptyBorder(20, 30, 20, 30));

        lblTotal = new JLabel("Total: $0.00");
        lblTotal.setFont(FONT_TOTAL);
        lblTotal.setForeground(Color.WHITE);
        lblTotal.setHorizontalAlignment(SwingConstants.RIGHT);

        totalPanel.add(lblTotal, BorderLayout.CENTER);
        rightPanel.add(totalPanel, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(420);
        splitPane.setDividerSize(0);
        splitPane.setBorder(null);

        mainContainer.add(splitPane, BorderLayout.CENTER);
    }

    private JLabel createLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_LABEL);
        l.setForeground(CLR_TEXT_MUTE);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel createSectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        l.setForeground(new Color(100, 100, 100));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private void log(String msg) {
        txtScreen.append(msg + "\n");
        txtScreen.setCaretPosition(txtScreen.getDocument().getLength());
    }

    private void refreshInventoryUI() {
        if (system == null) return;
        tableModel.setRowCount(0);
        List<Product> products = system.getAllProducts();
        products.sort(Comparator.comparing(p -> p.id));

        for (Product p : products) {
            tableModel.addRow(new Object[]{p.id, p.name, String.format("$%.2f", p.price), p.stock});
        }
    }

    private void checkAutoRefresh() {
        if (isNewTransaction) {
            txtScreen.setText("");
            txtScreen.append(">> NEW TRANSACTION STARTED...\n");
            lblTotal.setText("Total: $0.00");
            lblTotal.setForeground(Color.WHITE);
            isNewTransaction = false;
        }
    }

    private void prepareNextTransaction() {
        txtId.setText("");
        txtQty.setText("1");
        txtCash.setText("");
        isNewTransaction = true;
    }

    // Action Methods
    private void actionScan() {
        try {
            checkAutoRefresh();
            String id = txtId.getText().trim();
            int qty = Integer.parseInt(txtQty.getText().trim());

            String msg = system.recordSaleItem(id, qty);
            log(">> " + msg);
            lblTotal.setText(String.format("Total: $%.2f", system.getRunningTotal()));
            txtId.setText("");
            txtId.requestFocus();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Input Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void actionPay() {
        try {
            double cash = Double.parseDouble(txtCash.getText().trim());
            String receipt = system.processPayment(cash);

            log(">> PAYMENT AUTHORIZED.");
            log(">> INVENTORY UPDATED.");
            txtScreen.setText("");
            txtScreen.append(receipt);

            lblTotal.setText("PAID");
            lblTotal.setForeground(CLR_ACCENT_GREEN);

            refreshInventoryUI();
            prepareNextTransaction();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Payment Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionReturn() {
        try {
            checkAutoRefresh();
            String id = txtId.getText().trim();
            int qty = Integer.parseInt(txtQty.getText().trim());
            String msg = system.processReturn(id, qty);

            txtScreen.setText("");
            txtScreen.append(">> --- RETURN MODE ---\n");
            txtScreen.append(msg);

            lblTotal.setText("REFUND");
            lblTotal.setForeground(CLR_ACCENT_RED);

            refreshInventoryUI();
            prepareNextTransaction();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Return Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionAddProduct() {
        JPanel panel = new JPanel(new GridLayout(4, 2, 10, 10));
        panel.setPreferredSize(new Dimension(300, 150));

        ModernTextField fieldId = new ModernTextField();
        ModernTextField fieldName = new ModernTextField();
        ModernTextField fieldPrice = new ModernTextField();
        ModernTextField fieldStock = new ModernTextField();

        panel.add(new JLabel("New Item ID:"));
        panel.add(fieldId);
        panel.add(new JLabel("Item Name:"));
        panel.add(fieldName);
        panel.add(new JLabel("Price ($):"));
        panel.add(fieldPrice);
        panel.add(new JLabel("Initial Stock:"));
        panel.add(fieldStock);

        int result = JOptionPane.showConfirmDialog(this, panel, "Add New Product to Catalog",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            try {
                String id = fieldId.getText().trim();
                String name = fieldName.getText().trim();
                if (id.isEmpty() || name.isEmpty()) throw new Exception("ID and Name cannot be empty.");

                double price = Double.parseDouble(fieldPrice.getText().trim());
                int stock = Integer.parseInt(fieldStock.getText().trim());

                system.addProductToCatalog(id, name, price, stock);
                JOptionPane.showMessageDialog(this, "Product Added Successfully!");
                log(">> ADMIN: New Product Added (" + name + ")");
                refreshInventoryUI();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid Price or Stock number.", "Error", JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // Custom Components
    class ModernButton extends JButton {
        private Color normalColor;
        private Color hoverColor;

        public ModernButton(String text, Color baseColor) {
            super(text);
            this.normalColor = baseColor;
            this.hoverColor = baseColor.brighter();
            setFont(new Font("Segoe UI", Font.BOLD, 15));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));

            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { setBackground(hoverColor); repaint(); }
                public void mouseExited(MouseEvent e) { setBackground(normalColor); repaint(); }
            });
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isRollover()) g2.setColor(hoverColor);
            else g2.setColor(normalColor);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
            super.paintComponent(g);
            g2.dispose();
        }
    }

    class ModernTextField extends JTextField {
        private String placeholder = "";
        public ModernTextField() {
            setFont(FONT_INPUT);
            setBackground(CLR_INPUT_BG);
            setForeground(Color.WHITE);
            setCaretColor(Color.WHITE);
            setBorder(BorderFactory.createCompoundBorder(new LineBorder(CLR_PANEL, 0), new EmptyBorder(8, 10, 8, 10)));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        }
        public void setPlaceholder(String text) { this.placeholder = text; }
        @Override
        protected void paintComponent(Graphics pG) {
            super.paintComponent(pG);
            if (placeholder.length() > 0 && getText().length() == 0) {
                Graphics2D g = (Graphics2D) pG;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(150, 150, 150));
                g.setFont(getFont().deriveFont(Font.ITALIC));
                g.drawString(placeholder, getInsets().left, pG.getFontMetrics().getMaxAscent() + getInsets().top);
            }
        }
    }

    public static void main(String[] args) {
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) {
            JOptionPane.showMessageDialog(null, "SQLite Driver not found! Check classpath.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            new POSApplication().setVisible(true);
        });
    }
}