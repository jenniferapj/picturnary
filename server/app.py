import random
import uuid
import gevent
from flask import Flask, request
from flask_socketio import SocketIO, emit, join_room

app = Flask(__name__)
app.config['SECRET_KEY'] = 'picturnary-secret-key'
socketio = SocketIO(app, cors_allowed_origins="*", async_mode='gevent')

# --- Silly Team Name Generator ---
ADJECTIVES = [
    "Sleepy", "Grumpy", "Wobbly", "Sparkly", "Chunky",
    "Sneaky", "Fluffy", "Cranky", "Dizzy", "Sassy",
    "Bouncy", "Giggly", "Spicy", "Turbo", "Cosmic",
    "Funky", "Wacky", "Jazzy", "Mighty", "Zappy",
    "Cheesy", "Nifty", "Goofy", "Peppy", "Quirky",
    "Rowdy", "Snazzy", "Zippy", "Breezy", "Jolly",
]
NOUNS = [
    "Pickles", "Wombats", "Noodles", "Penguins", "Tacos",
    "Bananas", "Llamas", "Nuggets", "Pancakes", "Sloths",
    "Goblins", "Dumplings", "Narwhals", "Potatoes", "Waffles",
    "Cactuses", "Muffins", "Yetis", "Burritos", "Platypuses",
    "Turnips", "Badgers", "Croutons", "Flamingos", "Pickaxes",
    "Toasters", "Unicorns", "Noodle-Arms", "Belly-Floppers", "Toe-Beans",
]

# --- Word Bank ---
WORDS = [
    "elephant", "rainbow", "bicycle", "volcano", "penguin",
    "submarine", "pizza", "tornado", "telescope", "cactus",
    "lighthouse", "saxophone", "jellyfish", "skateboard", "mushroom",
    "windmill", "octopus", "helicopter", "pineapple", "snowman",
    "flamingo", "caterpillar", "waterfall", "spaceship", "umbrella",
    "butterfly", "sandcastle", "sunflower", "trampoline", "igloo",
    "parachute", "dragon", "popcorn", "carnival", "dinosaur",
    "mermaid", "treehouse", "giraffe", "fireworks", "basketball",
    "bathtub", "compass", "hammock", "kangaroo", "lollipop",
    "magnet", "treasure", "anchor", "trophy", "escalator",
    "wizard", "pirate", "robot", "castle", "rocket",
    "bridge", "crown", "guitar", "ladder", "mirror",
    "puzzle", "shark", "snail", "spider", "train",
    "turtle", "whale", "windmill", "witch", "zipper",
]

TOTAL_ROUNDS = 6     # 3 rounds each as drawer
ROUND_DURATION = 60  # seconds per round

# --- State ---
waiting_player = None
games = {}         # room_id -> game dict
player_rooms = {}  # sid -> room_id


def generate_team_name(exclude=None):
    while True:
        name = f"The {random.choice(ADJECTIVES)} {random.choice(NOUNS)}"
        if name != exclude:
            return name


def start_round(room_id):
    game = games.get(room_id)
    if not game:
        return

    game['round'] += 1
    game['guessed'] = False
    game['current_word'] = random.choice(WORDS)

    sids = list(game['players'].keys())
    drawer_idx = (game['round'] - 1) % 2
    drawer_sid = sids[drawer_idx]
    guesser_sid = sids[1 - drawer_idx]
    game['drawer'] = drawer_sid
    game['guesser'] = guesser_sid

    drawer_name = game['players'][drawer_sid]['team_name']
    guesser_name = game['players'][guesser_sid]['team_name']

    socketio.emit('round_start', {
        'round': game['round'],
        'total_rounds': TOTAL_ROUNDS,
        'role': 'drawer',
        'word': game['current_word'],
        'duration': ROUND_DURATION,
        'your_score': game['scores'][drawer_sid],
        'opponent_score': game['scores'][guesser_sid],
        'your_name': drawer_name,
        'opponent_name': guesser_name,
    }, to=drawer_sid, namespace='/')

    socketio.emit('round_start', {
        'round': game['round'],
        'total_rounds': TOTAL_ROUNDS,
        'role': 'guesser',
        'word': None,
        'duration': ROUND_DURATION,
        'your_score': game['scores'][guesser_sid],
        'opponent_score': game['scores'][drawer_sid],
        'your_name': guesser_name,
        'opponent_name': drawer_name,
    }, to=guesser_sid, namespace='/')

    # Start round timer in a background greenlet
    round_num = game['round']

    def run_timer():
        gevent.sleep(ROUND_DURATION)
        if room_id in games:
            g = games[room_id]
            if g['round'] == round_num and not g['guessed']:
                end_round(room_id, correct=False)

    gevent.spawn(run_timer)
    print(f"Round {game['round']}: {drawer_name} draws '{game['current_word']}'")


def end_round(room_id, correct):
    game = games.get(room_id)
    if not game or game.get('guessed'):
        return

    game['guessed'] = True
    word = game['current_word']
    current_round = game['round']
    guesser_sid = game['guesser']

    if correct:
        game['scores'][guesser_sid] += 1

    sids = list(game['players'].keys())
    for s in sids:
        other = [x for x in sids if x != s][0]
        socketio.emit('round_end', {
            'round': current_round,
            'total_rounds': TOTAL_ROUNDS,
            'word': word,
            'correct': correct,
            'your_score': game['scores'][s],
            'opponent_score': game['scores'][other],
        }, to=s, namespace='/')

    print(f"Round {current_round} ended: word='{word}', correct={correct}")

    def transition():
        gevent.sleep(4)  # Show result screen for 4 seconds then auto-advance
        if room_id not in games:
            return
        g = games[room_id]
        if g['round'] != current_round:
            return
        if current_round >= TOTAL_ROUNDS:
            send_game_over(room_id)
        else:
            start_round(room_id)

    gevent.spawn(transition)


def send_game_over(room_id):
    game = games.get(room_id)
    if not game:
        return

    sids = list(game['players'].keys())
    scores = game['scores']

    if scores[sids[0]] == scores[sids[1]]:
        winner_name = None
        is_tie = True
    elif scores[sids[0]] > scores[sids[1]]:
        winner_name = game['players'][sids[0]]['team_name']
        is_tie = False
    else:
        winner_name = game['players'][sids[1]]['team_name']
        is_tie = False

    for s in sids:
        other = [x for x in sids if x != s][0]
        socketio.emit('game_over', {
            'winner_name': winner_name,
            'your_score': scores[s],
            'opponent_score': scores[other],
            'is_tie': is_tie,
        }, to=s, namespace='/')

    print(f"Game over room {room_id}: {'Tie' if is_tie else f'{winner_name} wins'}")
    # Clean up
    for s in sids:
        player_rooms.pop(s, None)
    del games[room_id]


@app.route('/')
def index():
    return "Picturnary Server is running!"


@socketio.on('connect')
def on_connect():
    print(f"Connected: {request.sid}")


@socketio.on('disconnect')
def on_disconnect():
    global waiting_player
    sid = request.sid
    print(f"Disconnected: {sid}")

    if sid == waiting_player:
        waiting_player = None
        return

    room_id = player_rooms.pop(sid, None)
    if room_id and room_id in games:
        game = games[room_id]
        for other_sid in list(game['players']):
            if other_sid != sid:
                socketio.emit('opponent_disconnected', {}, to=other_sid, namespace='/')
                player_rooms.pop(other_sid, None)
        del games[room_id]


@socketio.on('join_game')
def on_join_game():
    global waiting_player
    sid = request.sid

    if waiting_player and waiting_player != sid:
        # Match found!
        room_id = str(uuid.uuid4())[:8]
        name1 = generate_team_name()
        name2 = generate_team_name(exclude=name1)

        game = {
            'room_id': room_id,
            'players': {
                waiting_player: {'team_name': name1},
                sid: {'team_name': name2},
            },
            'scores': {waiting_player: 0, sid: 0},
            'round': 0,
            'current_word': '',
            'drawer': None,
            'guesser': None,
            'guessed': False,
        }
        games[room_id] = game
        player_rooms[waiting_player] = room_id
        player_rooms[sid] = room_id

        join_room(room_id, sid=waiting_player)
        join_room(room_id, sid=sid)

        emit('game_start', {
            'room_id': room_id,
            'team_name': name1,
            'opponent_name': name2,
        }, to=waiting_player)

        emit('game_start', {
            'room_id': room_id,
            'team_name': name2,
            'opponent_name': name1,
        }, to=sid)

        print(f"Game started: {name1} vs {name2} (room {room_id})")
        waiting_player = None

        def start_first_round():
            gevent.sleep(1.5)
            if room_id in games:
                start_round(room_id)

        gevent.spawn(start_first_round)
    else:
        waiting_player = sid
        emit('waiting', {'message': 'Waiting for an opponent...'})
        print(f"Player {sid} is waiting")


@socketio.on('draw_stroke')
def on_draw_stroke(data):
    sid = request.sid
    room_id = player_rooms.get(sid)
    if not room_id or room_id not in games:
        return

    game = games[room_id]
    if game.get('drawer') != sid:
        return  # Only the drawer can send strokes

    guesser_sid = game.get('guesser')
    if guesser_sid:
        emit('draw_stroke', {
            'x': float(data.get('x', 0)),
            'y': float(data.get('y', 0)),
            'new_path': bool(data.get('new_path', False)),
            'color': int(data.get('color', -16777216)),  # default black
        }, to=guesser_sid)


@socketio.on('clear_canvas')
def on_clear_canvas():
    sid = request.sid
    room_id = player_rooms.get(sid)
    if not room_id or room_id not in games:
        return

    game = games[room_id]
    if game.get('drawer') != sid:
        return

    guesser_sid = game.get('guesser')
    if guesser_sid:
        emit('clear_canvas', {}, to=guesser_sid)


@socketio.on('submit_guess')
def on_submit_guess(data):
    sid = request.sid
    room_id = player_rooms.get(sid)
    if not room_id or room_id not in games:
        return

    game = games[room_id]
    if game.get('guesser') != sid or game.get('guessed'):
        return

    guess = data.get('guess', '').strip().lower()
    word = game.get('current_word', '').strip().lower()

    # Let the drawer see all guess attempts
    drawer_sid = game.get('drawer')
    guesser_name = game['players'][sid]['team_name']
    if drawer_sid:
        emit('guess_attempt', {
            'guess': guess,
            'guesser_name': guesser_name,
        }, to=drawer_sid)

    if guess == word:
        end_round(room_id, correct=True)
    else:
        emit('guess_wrong', {'guess': guess}, to=sid)


if __name__ == '__main__':
    import os
    port = int(os.environ.get('PORT', 5000))
    print(f"Starting Picturnary Server on port {port}...")
    socketio.run(app, host='0.0.0.0', port=port, debug=False)
