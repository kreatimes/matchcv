import 'package:dio/dio.dart';

// Emulador Android: 10.0.2.2 apunta al localhost de tu PC.
// Móvil físico: flutter run --dart-define=API_URL=http://IP_DE_TU_PC:8080
const apiUrl = String.fromEnvironment('API_URL', defaultValue: 'http://10.0.2.2:8080');

// Un camino profesional que la IA propone a partir del CV (p. ej. "Prácticas DAM")
class SuggestedSearch {
  final String name, role, keyword;

  SuggestedSearch.fromJson(Map<String, dynamic> j)
      : name = j['name'] ?? '',
        role = j['role'] ?? '',
        keyword = j['keyword'] ?? '';
}

class Profile {
  // JSON tal cual lo devuelve el backend: se guarda en el móvil y se reenvía al analizar páginas
  final Map<String, dynamic> json;
  final String role;
  final String? seniority, province;
  final int? years;
  final List<String> skills;
  final List<SuggestedSearch> suggestions;

  Profile.fromJson(this.json)
      : role = json['role'] ?? 'Sin puesto',
        seniority = json['seniority'],
        province = json['province'],
        years = json['yearsExperience'],
        skills = _strings(json['skills']),
        suggestions = ((json['suggestedSearches'] ?? []) as List)
            .map((s) => SuggestedSearch.fromJson(s as Map<String, dynamic>))
            .toList();
}

class Offer {
  final String title, source, description;
  final String? company, city, url;
  final int? salaryMin, salaryMax;
  final bool remote;

  Offer.fromJson(Map<String, dynamic> j)
      : title = j['title'] ?? '',
        source = j['source'] ?? '',
        description = j['description'] ?? '',
        company = j['company'],
        city = j['city'],
        url = j['url'],
        salaryMin = j['salaryMin'],
        salaryMax = j['salaryMax'],
        remote = j['remote'] ?? false;

  String? get salary => salaryMin == null
      ? null
      : salaryMax == null || salaryMax == salaryMin
          ? '${_k(salaryMin!)} €'
          : '${_k(salaryMin!)}-${_k(salaryMax!)} €';

  static String _k(int v) => '${(v / 1000).round()}k';
}

class Match {
  final Offer offer;
  final int score;
  final List<String> matches, missing;
  final String reason;

  Match.fromJson(Map<String, dynamic> j)
      : offer = Offer.fromJson(j['offer']),
        score = j['score'],
        matches = _strings(j['matches']),
        missing = _strings(j['missing']),
        reason = j['reason'] ?? '';
}

List<String> _strings(Object? json) => ((json ?? []) as List).map((e) => e.toString()).toList();

class Api {
  // La IA lee el CV o la página y puntúa: puede tardar bastante
  static final _dio = Dio(BaseOptions(baseUrl: apiUrl, receiveTimeout: const Duration(seconds: 120)));

  static Future<Profile> profile(String path, String filename) async {
    final form = FormData.fromMap({'file': await MultipartFile.fromFile(path, filename: filename)});
    final res = await _dio.post('/api/cv/profile', data: form);
    return Profile.fromJson(res.data);
  }

  // Manda el texto y los enlaces de la página que se está viendo; devuelve sus ofertas puntuadas
  static Future<List<Match>> analyzePage({
    required Profile profile,
    required String role,
    required String portal,
    required String text,
    required List<dynamic> links,
  }) async {
    final res = await _dio.post('/api/page/analyze', data: {
      'profile': profile.json,
      'role': role,
      'portal': portal,
      'text': text,
      'links': links,
    });
    return (res.data as List).map((m) => Match.fromJson(m)).toList();
  }

  // Mensaje legible: el del backend si lo hay ({"error": "..."}), si no uno genérico
  static String errorMessage(Object e) {
    if (e is DioException) {
      final data = e.response?.data;
      if (data is Map && data['error'] != null) return data['error'];
      if (e.type == DioExceptionType.connectionError) return 'No hay conexión con el servidor ($apiUrl)';
      if (e.type == DioExceptionType.receiveTimeout) return 'El servidor ha tardado demasiado';
    }
    return 'Algo ha fallado. Inténtalo de nuevo.';
  }
}
