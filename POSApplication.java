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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// --- Domain ---
class Product {
    String id, name;
    double price;
    int stock;
    public Product(String id, String name, double price, int stock) {
        this.id = id; this.name = name; this.price = price; this.stock = stock;
    }
}
class SaleItem {
    Product product; int quantity; double subtotal;
    public SaleItem(Product product, int quantity) {
        this.product = product; this.quantity = quantity; this.subtotal = product.price * quantity;
    }
}

// --- Logic (No DB) ---
class POSSystem {
    private Map<String, Product> inventory = new HashMap<>();
    private List<SaleItem> currentCart = new ArrayList<>();

    public POSSystem() {
        // Sprint 1: Hardcoded Data
        inventory.put("101", new Product("101", "Fresh Milk", 3.50, 20));
        inventory.put("102", new Product("102", "Bread", 5.00, 15));
        inventory.put("103", new Product("103", "Apple", 0.80, 100));
    }

    public Product getProduct(String id) { return inventory.get(id); }
    public List<Product> getAllProducts() { return new ArrayList<>(inventory.values()); }

    public String recordSaleItem(String id, int quantity) throws Exception {
        Product p = getProduct(id);
        if (p == null) throw new Exception("Product ID not found.");
        if (p.stock < quantity) throw new Exception("Insufficient stock.");
        
        SaleItem item = new SaleItem(p, quantity);
        currentCart.add(item);
        return String.format("Recorded: %s x%d = $%.2f", p.name, quantity, item.subtotal);
    }

    public double getRunningTotal() { return currentCart.stream().mapToDouble(i -> i.subtotal).sum(); }

    public String processPayment(double cash) throws Exception {
        double total = getRunningTotal();
        if (cash < total) throw new Exception("Insufficient cash.");
        
        // Simple update in memory
        for(SaleItem item : currentCart) {
            item.product.stock -= item.quantity;
        }
        
        String receipt = "Receipt Generated.\nTotal: $" + total + "\nPaid: $" + cash;
        currentCart.clear();
        return receipt;
    }
}

// --- UI (Basic Modern) ---
public class POSApplication extends JFrame {
    private POSSystem system;
    private JTextField txtId, txtQty, txtCash;
    private JTextArea txtScreen;
    private JTable tblInventory;
    private DefaultTableModel tableModel;
    
    public POSApplication() {
        system = new POSSystem();
        initUI();
        refreshTable();
    }

    private void initUI() {
        setTitle("POS System v1.0 (MVP)");
        setSize(1000, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(new Color(33,33,33));
        
        // Left Panel
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBackground(new Color(48,48,48));
        
        txtId = new JTextField(); txtQty = new JTextField("1"); txtCash = new JTextField();
        JButton btnScan = new JButton("Scan"); 
        btnScan.addActionListener(e -> scan());
        JButton btnPay = new JButton("Pay"); 
        btnPay.addActionListener(e -> pay());

        left.add(new JLabel("ID:"){{setForeground(Color.WHITE);}}); left.add(txtId);
        left.add(new JLabel("Qty:"){{setForeground(Color.WHITE);}}); left.add(txtQty);
        left.add(Box.createVerticalStrut(10));
        left.add(btnScan);
        left.add(Box.createVerticalStrut(20));
        left.add(new JLabel("Cash:"){{setForeground(Color.WHITE);}}); left.add(txtCash);
        left.add(btnPay);
        
        // Table
        tableModel = new DefaultTableModel(new String[]{"ID","Name","Stock"}, 0);
        tblInventory = new JTable(tableModel);
        left.add(new JScrollPane(tblInventory));

        // Right Panel
        txtScreen = new JTextArea();
        txtScreen.setBackground(Color.BLACK);
        txtScreen.setForeground(Color.GREEN);
        
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(txtScreen));
        split.setDividerLocation(400);
        main.add(split);
        add(main);
    }
    
    private void scan() {
        try {
            String msg = system.recordSaleItem(txtId.getText(), Integer.parseInt(txtQty.getText()));
            txtScreen.append(msg + "\n");
        } catch(Exception e) { JOptionPane.showMessageDialog(this, e.getMessage()); }
    }
    
    private void pay() {
        try {
            String r = system.processPayment(Double.parseDouble(txtCash.getText()));
            txtScreen.append(r + "\n");
            refreshTable();
        } catch(Exception e) { JOptionPane.showMessageDialog(this, e.getMessage()); }
    }
    
    private void refreshTable() {
        tableModel.setRowCount(0);
        for(Product p : system.getAllProducts()) tableModel.addRow(new Object[]{p.id, p.name, p.stock});
    }

    public static void main(String[] args) { new POSApplication().setVisible(true); }
}
