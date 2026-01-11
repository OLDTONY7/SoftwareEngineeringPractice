import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ==========================================
// 1. Model (数据模型)
// ==========================================
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

// ==========================================
// 2. Controller (后端逻辑 - 集成数据库)
// ==========================================
class POSSystem {
    private Map<String, Product> inventory = new HashMap<>();
    private List<SaleItem> currentCart = new ArrayList<>();
    
    // 数据库连接字符串
    private final String DB_URL = "jdbc:sqlite:pos.db";

    public POSSystem() {
        initDatabase();
        loadInventoryFromDB();
    }

    // --- 数据库操作 ---

    private void initDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            // 创建表
            String sqlCreate = "CREATE TABLE IF NOT EXISTS products (" +
                               "id TEXT PRIMARY KEY, " +
                               "name TEXT, " +
                               "price REAL, " +
                               "stock INTEGER)";
            stmt.execute(sqlCreate);
            
            // 如果表为空，插入初始数据
            ResultSet rs = stmt.executeQuery("SELECT count(*) FROM products");
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.execute("INSERT INTO products VALUES ('101', 'Fresh Milk', 3.50, 20)");
                stmt.execute("INSERT INTO products VALUES ('102', 'Bread', 5.00, 15)");
                stmt.execute("INSERT INTO products VALUES ('103', 'Apple', 0.80, 100)");
                stmt.execute("INSERT INTO products VALUES ('104', 'Cola', 1.50, 50)");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadInventoryFromDB() {
        inventory.clear();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM products")) {
            
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
            e.printStackTrace();
        }
    }

    private void updateProductStockInDB(String id, int newStock) {
        String sql = "UPDATE products SET stock = ? WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newStock);
            pstmt.setString(2, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- 业务逻辑 ---

    public Product getProduct(String id) {
        return inventory.get(id);
    }
    
    public List<Product> getAllProducts() {
        return new ArrayList<>(inventory.values());
    }

    // 功能 1: 销售扫描
    public String recordSaleItem(String id, int quantity) throws Exception {
        Product p = getProduct(id);
        if (p == null) throw new Exception("Error: Product ID not found.");
        if (p.stock < quantity) throw new Exception("Error: Insufficient stock.");

        SaleItem item = new SaleItem(p, quantity);
        currentCart.add(item);
        return String.format("Recorded: %s x%d = $%.2f", p.name, quantity, item.subtotal);
    }

    // 功能 2: 支付 (扣减库存)
    public String processPayment(double cash) throws Exception {
        double total = currentCart.stream().mapToDouble(i -> i.subtotal).sum();
        if (currentCart.isEmpty()) throw new Exception("Cart is empty.");
        if (cash < total) throw new Exception("Insufficient cash.");

        for (SaleItem item : currentCart) {
            item.product.stock -= item.quantity; // 更新内存
            updateProductStockInDB(item.product.id, item.product.stock); // 更新数据库
        }
        
        String receipt = "Receipt Generated.\nTotal: $" + String.format("%.2f", total) + 
                         "\nChange: $" + String.format("%.2f", cash - total);
        currentCart.clear();
        return receipt;
    }

    // 功能 3: 退货 (增加库存) - [Commit 2 新增功能]
    public String processReturn(String id, int quantity) throws Exception {
        Product p = getProduct(id);
        if (p == null) throw new Exception("Error: Product ID not found.");

        p.stock += quantity; // 更新内存
        updateProductStockInDB(p.id, p.stock); // 更新数据库
        
        return String.format("Returned: %s x%d\nRefund Amount: $%.2f", p.name, quantity, p.price * quantity);
    }
}

// ==========================================
// 3. UI Layer (标准 Swing 界面)
// ==========================================
public class POSApplication extends JFrame {
    private POSSystem system;
    private JTextField txtId, txtQty, txtCash;
    private JTextArea txtScreen;
    private JTable tblInventory;
    private DefaultTableModel tableModel;

    public POSApplication() {
        // 尝试连接系统
        system = new POSSystem();
        initUI();
        refreshInventoryTable();
    }

    private void initUI() {
        setTitle("POS System v2.0 (With Database & Returns)");
        setSize(1000, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // 主容器
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 左侧控制面板
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setPreferredSize(new Dimension(400, 0));

        // 输入框
        txtId = new JTextField();
        txtQty = new JTextField("1");
        txtCash = new JTextField();

        leftPanel.add(new JLabel("Item ID:"));
        leftPanel.add(txtId);
        leftPanel.add(Box.createVerticalStrut(5));
        
        leftPanel.add(new JLabel("Quantity:"));
        leftPanel.add(txtQty);
        leftPanel.add(Box.createVerticalStrut(15));

        // 按钮区域
        JButton btnScan = new JButton("Scan Item");
        JButton btnReturn = new JButton("Handle Return"); // 新增退货按钮
        
        btnScan.addActionListener(e -> actionScan());
        btnReturn.addActionListener(e -> actionReturn());

        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 5, 0));
        btnPanel.add(btnScan);
        btnPanel.add(btnReturn);
        btnPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        leftPanel.add(btnPanel);
        
        leftPanel.add(Box.createVerticalStrut(15));
        leftPanel.add(new JLabel("Cash Tendered ($):"));
        leftPanel.add(txtCash);
        leftPanel.add(Box.createVerticalStrut(5));
        
        JButton btnPay = new JButton("Process Payment");
        btnPay.addActionListener(e -> actionPay());
        leftPanel.add(btnPay);

        leftPanel.add(Box.createVerticalStrut(20));
        leftPanel.add(new JLabel("Current Inventory (From DB):"));

        // 库存表格
        String[] columns = {"ID", "Name", "Price", "Stock"};
        tableModel = new DefaultTableModel(columns, 0);
        tblInventory = new JTable(tableModel);
        leftPanel.add(new JScrollPane(tblInventory));

        // 右侧屏幕
        txtScreen = new JTextArea();
        txtScreen.setEditable(false);
        txtScreen.setFont(new Font("Monospaced", Font.BOLD, 14));
        txtScreen.setText(">> System Connected to SQLite DB.\n>> Ready.\n");
        JScrollPane scrollScreen = new JScrollPane(txtScreen);

        // 分割线布局
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, scrollScreen);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        add(mainPanel);
    }

    // --- 事件处理 ---

    private void actionScan() {
        try {
            String id = txtId.getText().trim();
            int qty = Integer.parseInt(txtQty.getText().trim());
            String msg = system.recordSaleItem(id, qty);
            txtScreen.append(msg + "\n");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionPay() {
        try {
            double cash = Double.parseDouble(txtCash.getText().trim());
            String receipt = system.processPayment(cash);
            txtScreen.append("----------------\n" + receipt + "\n----------------\n");
            refreshInventoryTable(); // 支付成功后刷新库存
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Payment Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void actionReturn() {
        try {
            String id = txtId.getText().trim();
            int qty = Integer.parseInt(txtQty.getText().trim());
            String msg = system.processReturn(id, qty);
            txtScreen.append(">> [RETURN] " + msg + "\n");
            refreshInventoryTable(); // 退货成功后刷新库存
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Return Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshInventoryTable() {
        tableModel.setRowCount(0);
        for (Product p : system.getAllProducts()) {
            tableModel.addRow(new Object[]{p.id, p.name, p.price, p.stock});
        }
    }

    public static void main(String[] args) {
        // 检查驱动
        try { Class.forName("org.sqlite.JDBC"); } 
        catch (ClassNotFoundException e) {
            JOptionPane.showMessageDialog(null, "SQLite Driver missing!");
            return;
        }
        
        SwingUtilities.invokeLater(() -> {
            new POSApplication().setVisible(true);
        });
    }
}
