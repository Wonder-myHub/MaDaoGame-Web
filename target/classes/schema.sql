CREATE TABLE IF NOT EXISTS game_room (
                                         id VARCHAR(36) PRIMARY KEY,
    player_count INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    round INT DEFAULT 0,
    phase VARCHAR(20) DEFAULT 'GUESS',
    winner VARCHAR(50)
    );

CREATE TABLE IF NOT EXISTS player (
                                      id VARCHAR(36) PRIMARY KEY,
    room_id VARCHAR(36) NOT NULL,
    name VARCHAR(50) NOT NULL,
    hp INT DEFAULT 10,
    steps INT DEFAULT 0,
    horse BOOLEAN DEFAULT FALSE,
    knife BOOLEAN DEFAULT FALSE,
    buff BOOLEAN DEFAULT FALSE,
    location VARCHAR(30),
    alive BOOLEAN DEFAULT TRUE,
    guess VARCHAR(10),
    FOREIGN KEY (room_id) REFERENCES game_room(id)
    );