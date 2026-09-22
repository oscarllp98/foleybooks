-- CA-04 / FR-14 / D-12: seeded read-only catalog — 3 categories + 12 real books
-- (ADR-003). Fixed UUIDs mirror ADR-002's demo users so CA-06..CA-11 tests can
-- reference rows by stable id instead of title lookup.
-- cover_url is the OpenLibrary large-size hotlink keyed by the row's own ISBN
-- (D-11); README attribution lands with WR-03. Prices are list EUR at scale 2
-- (D-08). Stock spans all three D-09 availability buckets without touching the
-- database: 0 → OUT_OF_STOCK (The Great Gatsby); 1–5 → LOW_STOCK (Zero to One 2,
-- The Catcher in the Rye 3, The Pragmatic Programmer 5 — the bucket's upper
-- edge); >5 → IN_STOCK (Good to Great 6 sits just above that edge).

INSERT INTO categories (id, name)
VALUES ('00000000-0000-0000-0000-00000000ca01', 'Fiction'),
       ('00000000-0000-0000-0000-00000000ca02', 'Technology'),
       ('00000000-0000-0000-0000-00000000ca03', 'Business');

INSERT INTO books (id, category_id, title, author, isbn, price, stock_quantity, cover_url)
VALUES ('00000000-0000-0000-0000-00000000cb01', '00000000-0000-0000-0000-00000000ca01',
        'To Kill a Mockingbird', 'Harper Lee', '9780061120084', 12.99, 14,
        'https://covers.openlibrary.org/b/isbn/9780061120084-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb02', '00000000-0000-0000-0000-00000000ca01',
        '1984', 'George Orwell', '9780451524935', 10.99, 22,
        'https://covers.openlibrary.org/b/isbn/9780451524935-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb03', '00000000-0000-0000-0000-00000000ca01',
        'The Great Gatsby', 'F. Scott Fitzgerald', '9780743273565', 11.50, 0,
        'https://covers.openlibrary.org/b/isbn/9780743273565-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb04', '00000000-0000-0000-0000-00000000ca01',
        'Pride and Prejudice', 'Jane Austen', '9780141439518', 8.99, 31,
        'https://covers.openlibrary.org/b/isbn/9780141439518-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb05', '00000000-0000-0000-0000-00000000ca01',
        'The Catcher in the Rye', 'J. D. Salinger', '9780316769488', 14.99, 3,
        'https://covers.openlibrary.org/b/isbn/9780316769488-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb06', '00000000-0000-0000-0000-00000000ca02',
        'Clean Code', 'Robert C. Martin', '9780132350884', 31.99, 12,
        'https://covers.openlibrary.org/b/isbn/9780132350884-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb07', '00000000-0000-0000-0000-00000000ca02',
        'The Pragmatic Programmer', 'Andrew Hunt, David Thomas', '9780135957059', 44.99, 5,
        'https://covers.openlibrary.org/b/isbn/9780135957059-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb08', '00000000-0000-0000-0000-00000000ca02',
        'Designing Data-Intensive Applications', 'Martin Kleppmann', '9781449373320', 49.99, 9,
        'https://covers.openlibrary.org/b/isbn/9781449373320-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb09', '00000000-0000-0000-0000-00000000ca02',
        'Introduction to Algorithms', 'Thomas H. Cormen', '9780262033848', 64.99, 17,
        'https://covers.openlibrary.org/b/isbn/9780262033848-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb0a', '00000000-0000-0000-0000-00000000ca03',
        'Thinking, Fast and Slow', 'Daniel Kahneman', '9780374533557', 15.99, 9,
        'https://covers.openlibrary.org/b/isbn/9780374533557-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb0b', '00000000-0000-0000-0000-00000000ca03',
        'Zero to One', 'Peter Thiel', '9780804139298', 13.50, 2,
        'https://covers.openlibrary.org/b/isbn/9780804139298-L.jpg'),
       ('00000000-0000-0000-0000-00000000cb0c', '00000000-0000-0000-0000-00000000ca03',
        'Good to Great', 'Jim Collins', '9780066620992', 18.99, 6,
        'https://covers.openlibrary.org/b/isbn/9780066620992-L.jpg');
