import 'package:dio/dio.dart';

// Emulador Android: 10.0.2.2 apunta al localhost de tu PC.
// Móvil físico: flutter run --dart-define=API_URL=http://IP_DE_TU_PC:8080
const apiUrl = String.fromEnvironment('API_URL', defaultValue: 'http://10.0.2.2:8080');

class Profile {
  final String role;
  final String? seniority, province;
  final int? years;
  final List<String> skills, keywords;

  Profile.fromJson(Map<String, dynamic> j)
      : role = j['role'] ?? 'Sin puesto',
        seniority = j['seniority'],
        province = j['province'],
        years = j['yearsExperience'],
        skills = _strings(j['skills']),
        keywords = _strings(j['searchKeywords']);
}

class Offer {
  final String title, source;
  final String? company, city, url;
  final int? salaryMin, salaryMax;
  final bool remote;

  Offer.fromJson(Map<String, dynamic> j)
      : title = j['title'] ?? '',
        source = j['source'] ?? '',
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

class MatchResult {
  final Profile profile;
  final int offersFound;
  final List<Match> matches;

  MatchResult.fromJson(Map<String, dynamic> j)
      : profile = Profile.fromJson(j['profile']),
        offersFound = j['offersFound'],
        matches = (j['matches'] as List).map((m) => Match.fromJson(m)).toList();
}

List<String> _strings(Object? json) => ((json ?? []) as List).map((e) => e.toString()).toList();

class Api {
  // La IA lee el CV, busca y puntúa: puede tardar bastante
  static final _dio = Dio(BaseOptions(baseUrl: apiUrl, receiveTimeout: const Duration(seconds: 120)));

  static Future<MatchResult> match(String path, String filename, {String? province}) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(path, filename: filename),
      if (province != null && province.isNotEmpty) 'province': province,
    });
    final res = await _dio.post('/api/match', data: form);
    return MatchResult.fromJson(res.data);
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
