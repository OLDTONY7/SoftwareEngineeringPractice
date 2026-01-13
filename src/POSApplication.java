import javax.swing.;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.;
import java.sql.;  [关键] 引入SQL包
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

 ==========================================
 1. Model (数据模型)
 ==========================================

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
        this.subtotal = product.price  quantity;
    }
}

 ==========================================
 2. ControllerService (数据库版本)
 ==========================================

class POSSystem {
    private MapString, Product inventory = new HashMap();
    private ListSaleItem currentCart = new ArrayList();
    
     数据库连接字符串 (会自动在当前目录下创建 pos.db 文件)
    private final String DB_URL = jdbcsqlitepos.db;

    public POSSystem() {
        initDatabase();  初始化数据库表
        loadInventoryFromDB();  从数据库加载数据到内存
    }

     --- 数据库核心方法 ---

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private void initDatabase() {
         SQL 创建表
        String sqlCreate = CREATE TABLE IF NOT EXISTS products (
                + id TEXT PRIMARY KEY, 
                + name TEXT NOT NULL, 
                + price REAL, 
                + stock INTEGER);

        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            
             1. 创建表
            stmt.execute(sqlCreate);
            
             2. 检查是否为空，如果为空插入默认数据
            ResultSet rs = stmt.executeQuery(SELECT count() FROM products);
            if (rs.next() && rs.getInt(1) == 0) {
                System.out.println(DB Table empty, inserting defaults...);
                insertDefaultProduct(conn, 101, Fresh Milk, 3.50, 20);
                insertDefaultProduct(conn, 102, Sourdough Bread, 5.00, 15);
                insertDefaultProduct(conn, 103, Apple (Red), 0.80, 100);
                insertDefaultProduct(conn, 104, Cola Can, 1.50, 50);
                insertDefaultProduct(conn, 105, Chocolate Bar, 2.00, 30);
            }

        } catch (SQLException e) {
            System.err.println(DB Init Error  + e.getMessage());
        }
    }

    private void insertDefaultProduct(Connection conn, String id, String name, double price, int stock) throws SQLException {
        String sql = INSERT INTO products(id, name, price, stock) VALUES(,,,);
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            pstmt.setString(2, name);
            pstmt.setDouble(3, price);
            pstmt.setInt(4, stock);
            pstmt.executeUpdate();
        }
    }

     从数据库加载所有数据到 inventory Map
    private void loadInventoryFromDB() {
        inventory.clear();
        String sql = SELECT id, name, price, stock FROM products;
        
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                Product p = new Product(
                    rs.getString(id),
                    rs.getString(name),
                    rs.getDouble(price),
                    rs.getInt(stock)
                );
                inventory.put(p.id, p);
            }
            System.out.println(System Inventory loaded from Database.);
            
        } catch (SQLException e) {
            System.err.println(Load Error  + e.getMessage());
        }
    }

     更新单个商品的库存 (事务处理)
    private void updateProductStockInDB(String productId, int newStock) {
        String sql = UPDATE products SET stock =  WHERE id = ;
        try (Connection conn = connect();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newStock);
            pstmt.setString(2, productId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println(Update Stock Error  + e.getMessage());
        }
    }

     --- 业务功能 ---

    public Product getProduct(String id) {
        return inventory.get(id);
    }

    public String getInventoryStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(%-5s %-18s %-7s %sn, ID, Name, Price, Stock));
        sb.append(------------------------------------------n);
        inventory.values().stream()
                .sorted(Comparator.comparing(p - p.id))
                .forEach(p - {
                    sb.append(String.format(%-5s %-18s $%-6.2f [%d]n, p.id, p.name, p.price, p.stock));
                });
        return sb.toString();
    }

     功能 1 记录销售项
    public String recordSaleItem(String id, int quantity) throws Exception {
        Product p = getProduct(id);
        if (p == null) throw new Exception(Error Product ID not found.);

        int currentCartQty = currentCart.stream()
                .filter(item - item.product.id.equals(id))
                .mapToInt(item - item.quantity)
                .sum();

        if ((p.stock - currentCartQty)  quantity) {
            throw new Exception(Error Insufficient stock. Only  + p.stock +  available.);
        }

        SaleItem item = new SaleItem(p, quantity);
        currentCart.add(item);
        
        return String.format(Recorded %s x%d = $%.2f, p.name, quantity, item.subtotal);
    }

    public double getRunningTotal() {
        return currentCart.stream().mapToDouble(i - i.subtotal).sum();
    }

     功能 2 支付 (更新 DB)
    public String processPayment(double cashTendered) throws Exception {
        double total = getRunningTotal();
        if (currentCart.isEmpty()) throw new Exception(Cart is empty.);
        if (cashTendered  total) throw new Exception(String.format(Insufficient cash. Need $%.2f, total));

        double change = cashTendered - total;

         更新库存
        for (SaleItem item  currentCart) {
             1. 更新内存
            item.product.stock -= item.quantity;
             2. [关键] 更新数据库
            updateProductStockInDB(item.product.id, item.product.stock);
        }

        String receipt = generateReceipt(cashTendered, change);
        currentCart.clear();
        return receipt;
    }

     功能 3 退货 (更新 DB)
    public String processReturn(String id, int quantity) throws Exception {
        Product p = getProduct(id);
        if (p == null) throw new Exception(Error Product ID not found.);

         1. 更新内存
        p.stock += quantity;
        double refund = p.price  quantity;

         2. [关键] 更新数据库
        updateProductStockInDB(p.id, p.stock);

        return String.format(Return Processed %s x%dnInventory Updated +%dnRefund Amount $%.2f, 
                p.name, quantity, quantity, refund);
    }

    private String generateReceipt(double cash, double change) {
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(yyyy-MM-dd HHmmss);
        sb.append(n==================================n);
        sb.append(       OFFICIAL RECEIPTn);
        sb.append(        + dtf.format(LocalDateTime.now()) + n);
        sb.append(==================================n);
        sb.append(String.format(%-15s %-5s %sn, Item, Qty, Price));
        sb.append(----------------------------------n);
        
        double total = 0;
        for (SaleItem item  currentCart) {
            sb.append(String.format(%-15s x%-4d $%.2fn, item.product.name, item.quantity, item.subtotal));
            total += item.subtotal;
        }
        
        sb.append(----------------------------------n);
        sb.append(String.format(TOTAL          $%.2fn, total));
        sb.append(String.format(CASH PAID      $%.2fn, cash));
        sb.append(String.format(CHANGE         $%.2fn, change));
        sb.append(==================================n);
        sb.append(    Thank you for shopping!);
        return sb.toString();
    }
}

 ==========================================
 3. View (前端 UI - 保持不变)
 ==========================================

public class POSApplication extends JFrame {
    
    private POSSystem system;
    private JTextField txtId, txtQty, txtCash;
    private JTextArea txtScreen, txtInventory;
    private JLabel lblTotal;

     Dark Theme Colors
    private final Color COL_MAIN_BG = new Color(30, 30, 30);
    private final Color COL_PANEL_BG = new Color(50, 50, 50);
    private final Color COL_INPUT_BG = new Color(70, 70, 70);
    private final Color COL_TEXT_WHT = new Color(230, 230, 230);
    private final Color COL_ACCENT_GREEN = new Color(46, 204, 113);
    private final Color COL_ACCENT_BLUE = new Color(52, 152, 219);
    private final Color COL_ACCENT_RED = new Color(231, 76, 60);

    public POSApplication() {
         在实例化 POSSystem 时，会自动连接数据库
        system = new POSSystem();
        initUI();
        refreshInventoryUI();
    }

    private void initUI() {
        setTitle(POS System - SQLite Database Version);
        setSize(1100, 750);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new GridLayout(1, 2)); 

         === Left Panel ===
        JPanel leftPanel = new JPanel(new GridBagLayout());
        leftPanel.setBackground(COL_PANEL_BG);
        leftPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 5, 8, 5);
        gbc.weightx = 1.0;
        gbc.gridx = 0; gbc.gridy = 0;

        JLabel lblHeader = new JLabel(Cashier Controls (DB));
        lblHeader.setFont(new Font(Segoe UI, Font.BOLD, 22));
        lblHeader.setForeground(COL_TEXT_WHT);
        leftPanel.add(lblHeader, gbc);

        gbc.gridy++; leftPanel.add(createLabel(Item ID), gbc);
        txtId = createField(); 
        gbc.gridy++; leftPanel.add(txtId, gbc);

        gbc.gridy++; leftPanel.add(createLabel(Quantity), gbc);
        txtQty = createField(); txtQty.setText(1);
        gbc.gridy++; leftPanel.add(txtQty, gbc);

        addSeparator(leftPanel, gbc, Process Sale);
        JButton btnScan = createButton(Scan Item, COL_ACCENT_BLUE);
        btnScan.addActionListener(e - actionScan());
        gbc.gridy++; leftPanel.add(btnScan, gbc);

        gbc.gridy++; leftPanel.add(createLabel(Cash Tendered ($)), gbc);
        txtCash = createField();
        gbc.gridy++; leftPanel.add(txtCash, gbc);

        JButton btnPay = createButton(Pay & Print Receipt, COL_ACCENT_GREEN);
        btnPay.addActionListener(e - actionPay());
        gbc.gridy++; leftPanel.add(btnPay, gbc);

        addSeparator(leftPanel, gbc, Handle Returns);
        JButton btnReturn = createButton(Process Return, COL_ACCENT_RED);
        btnReturn.addActionListener(e - actionReturn());
        gbc.gridy++; leftPanel.add(btnReturn, gbc);

        addSeparator(leftPanel, gbc, Live Inventory (From DB));
        txtInventory = new JTextArea(10, 30);
        txtInventory.setFont(new Font(Monospaced, Font.PLAIN, 12));
        txtInventory.setBackground(new Color(40, 40, 40));
        txtInventory.setForeground(Color.CYAN);
        txtInventory.setEditable(false);
        txtInventory.setBorder(new LineBorder(Color.DARK_GRAY));
        gbc.gridy++; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        leftPanel.add(new JScrollPane(txtInventory), gbc);

         === Right Panel ===
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(COL_MAIN_BG);
        rightPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

        txtScreen = new JTextArea();
        txtScreen.setFont(new Font(Monospaced, Font.BOLD, 14));
        txtScreen.setBackground(Color.BLACK);
        txtScreen.setForeground(Color.GREEN);
        txtScreen.setEditable(false);
        txtScreen.setText( SYSTEM READY...n Connected to SQLite Database.n);
        txtScreen.setMargin(new Insets(15, 15, 15, 15));
        
        rightPanel.add(new JScrollPane(txtScreen), BorderLayout.CENTER);

        lblTotal = new JLabel(Total $0.00, SwingConstants.CENTER);
        lblTotal.setFont(new Font(Segoe UI, Font.BOLD, 48));
        lblTotal.setForeground(COL_TEXT_WHT);
        lblTotal.setBorder(new EmptyBorder(20, 0, 0, 0));
        rightPanel.add(lblTotal, BorderLayout.SOUTH);

        add(leftPanel);
        add(rightPanel);
    }

    private JLabel createLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.LIGHT_GRAY);
        l.setFont(new Font(Segoe UI, Font.PLAIN, 14));
        return l;
    }
    private JTextField createField() {
        JTextField t = new JTextField();
        t.setFont(new Font(Segoe UI, Font.PLAIN, 16));
        t.setBackground(COL_INPUT_BG);
        t.setForeground(Color.WHITE);
        t.setCaretColor(Color.WHITE);
        t.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Color.GRAY), new EmptyBorder(5, 5, 5, 5)));
        return t;
    }
    private JButton createButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setFont(new Font(Segoe UI, Font.BOLD, 14));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        return b;
    }
    private void addSeparator(JPanel p, GridBagConstraints gbc, String text) {
        gbc.gridy++; gbc.weighty = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
        JLabel l = new JLabel(text);
        l.setForeground(Color.GRAY);
        l.setBorder(new EmptyBorder(15, 0, 5, 0));
        p.add(l, gbc);
    }

    private void log(String msg) {
        txtScreen.append(msg + n);
        txtScreen.setCaretPosition(txtScreen.getDocument().getLength());
    }
    private void refreshInventoryUI() {
        txtInventory.setText(system.getInventoryStatus());
    }

    private void actionScan() {
        try {
            String id = txtId.getText().trim();
            int qty = Integer.parseInt(txtQty.getText().trim());
            String msg = system.recordSaleItem(id, qty);
            log(  + msg);
            lblTotal.setText(String.format(Total $%.2f, system.getRunningTotal()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), Error, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionPay() {
        try {
            double cash = Double.parseDouble(txtCash.getText().trim());
            String receipt = system.processPayment(cash);
            log( Payment Accepted.);
            log( Database Updated.);
            log( Printing Receipt...);
            txtScreen.setText();
            txtScreen.append(receipt);
            lblTotal.setText(Transaction Done);
            refreshInventoryUI();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), Payment Error, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionReturn() {
        try {
            String id = txtId.getText().trim();
            int qty = Integer.parseInt(txtQty.getText().trim());
            String msg = system.processReturn(id, qty);
            txtScreen.setText();
            txtScreen.append( --- RETURN MODE ---n);
            txtScreen.append(msg);
            lblTotal.setText(Refund Mode);
            refreshInventoryUI();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), Return Error, JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
         加载驱动检查 (可选，用于调试)
        try {
            Class.forName(org.sqlite.JDBC);
        } catch (ClassNotFoundException e) {
            JOptionPane.showMessageDialog(null, SQLite JDBC Driver not found!nPlease check classpath.);
            return;
        }

        SwingUtilities.invokeLater(() - {
            new POSApplication().setVisible(true);
        });
    }
}
